package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.File;

/**
 * The remote's power button (remapped to KEYCODE_STB_POWER in the IR key layout): plays media/outro.mp4 full screen
 * over whatever is on, with its sound, then puts the TV to sleep. A second press while it plays sleeps at once. If the
 * video is missing or fails, the press simply sleeps the TV, so the button always keeps working.
 * Runs inside the accessibility service (InputKeyService); the picture is an accessibility overlay window.
 */
final class PowerOutro {
    static final String FILE_NAME = "outro.mp4";
    /** Sleep anyway after this long, whatever the video does. */
    private static final long MAX_MS = 12_000L;
    /** A press this soon after the screen came on is the one that woke the TV, not a new request. */
    private static final long WAKE_GUARD_MS = 1_500L;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::sleepNow;
    private FrameLayout overlay;
    private MediaPlayer player;
    private Surface surface;
    private long lastScreenOn;
    private boolean registered;

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            lastScreenOn = SystemClock.elapsedRealtime();
        }
    };

    PowerOutro(AccessibilityService service) {
        this.service = service;
        try {
            service.registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
            registered = true;
        } catch (RuntimeException ignored) {
        }
    }

    static File file(Context context) {
        File base = context.getExternalFilesDir(null);
        return base == null ? null : new File(new File(base, "media"), FILE_NAME);
    }

    /** One press of the power button. */
    void onPowerPress() {
        if (overlay != null) {   // already playing: sleep now
            sleepNow();
            return;
        }
        if (SystemClock.elapsedRealtime() - lastScreenOn < WAKE_GUARD_MS) return;
        File file = file(service);
        if (file == null || !file.isFile() || !show(file)) lockScreen();
    }

    void release() {
        cleanup();
        if (registered) {
            try {
                service.unregisterReceiver(screenOn);
            } catch (RuntimeException ignored) {
            }
            registered = false;
        }
    }

    private boolean show(File file) {
        try {
            WindowManager wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
            FrameLayout layout = new FrameLayout(service);
            layout.setBackgroundColor(Color.BLACK);
            TextureView texture = new TextureView(service);
            layout.addView(texture, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.OPAQUE);
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    surface = new Surface(st);
                    play(file);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    if (surface != null) {
                        surface.release();
                        surface = null;
                    }
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                }
            });
            wm.addView(layout, params);
            overlay = layout;
            handler.postDelayed(timeout, MAX_MS);
            return true;
        } catch (RuntimeException e) {
            cleanup();
            return false;
        }
    }

    private void play(File file) {
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setDataSource(file.getAbsolutePath());
            mp.setSurface(surface);
            mp.setOnPreparedListener(p -> {
                if (player == p) p.start();
            });
            mp.setOnCompletionListener(p -> sleepNow());
            mp.setOnErrorListener((p, what, extra) -> {
                sleepNow();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            sleepNow();
        }
    }

    private void sleepNow() {
        // Keep the black overlay up while Home and the lock happen underneath, so Home never shows.
        handler.removeCallbacks(timeout);
        MediaPlayer old = player;
        player = null;
        if (old != null) {
            try {
                old.setOnPreparedListener(null);
                old.setOnCompletionListener(null);
                old.setOnErrorListener(null);
                old.release();
            } catch (RuntimeException ignored) {
            }
        }
        try {
            service.getSharedPreferences("boot", Context.MODE_PRIVATE).edit().putBoolean("intro_wake", true)
                    .putLong("intro_wake_at", android.os.SystemClock.elapsedRealtime()).commit();
        } catch (RuntimeException ignored) {
        }
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        handler.postDelayed(this::lockScreen, 600);
        handler.postDelayed(this::cleanup, 3000);
    }

    private void lockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        }
    }

    private void cleanup() {
        handler.removeCallbacks(timeout);
        MediaPlayer old = player;
        player = null;
        if (old != null) {
            try {
                old.setOnPreparedListener(null);
                old.setOnCompletionListener(null);
                old.setOnErrorListener(null);
                old.release();
            } catch (RuntimeException ignored) {
            }
        }
        FrameLayout view = overlay;
        overlay = null;
        if (view != null) {
            try {
                ((WindowManager) service.getSystemService(Context.WINDOW_SERVICE)).removeView(view);
            } catch (RuntimeException ignored) {
            }
        }
    }
}
