package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home background made of the owner's own pictures and videos, read from the app's media folder
 * (Android/data/com.gathin.tvhome/files/media):
 *   mine/    played in file-name order, 30 seconds each (a video shorter than that loops);
 *   filler/  one picture picked at random after each full pass of mine/.
 * Folders are listed again at the start of every pass, so files pushed later are picked up without a rebuild.
 * Videos are muted. No network is involved.
 */
final class MediaBackground {
    private static final long SLOT_MS = 30_000L;
    private static final int SCRIM = 0x4D000000;   // 30 % black over the media so tiles and text stay readable

    private final FrameLayout root;
    private final FrameLayout holder;
    private final TextureView video;
    private final ImageView image;
    private final File mediaDir;
    private final int width;
    private final int height;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final Runnable advanceTask = this::advance;

    private List<File> mine = new ArrayList<>();
    private List<File> filler = new ArrayList<>();
    private int pos;
    private File lastFiller;
    private File shown;
    private int token;
    private boolean active;
    private boolean closed;
    private Bitmap current;
    private Surface surface;
    private MediaPlayer player;
    private File pendingVideo;
    private int pendingToken;

    /** The background, or null when the media folder holds nothing (Home then behaves as before). */
    interface ColorListener { void onColor(int rgb); }
    private ColorListener colorListener;

    void setColorListener(ColorListener l) {
        colorListener = l;
    }

    private void reportColor(Bitmap bitmap) {
        ColorListener l = colorListener;
        if (l == null || bitmap == null) return;
        try {
            Bitmap small = Bitmap.createScaledBitmap(bitmap, 8, 8, true);
            long r = 0, g = 0, b = 0;
            for (int y = 3; y < 8; y++) for (int x = 0; x < 8; x++) {
                int c = small.getPixel(x, y);
                r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF;
            }
            if (small != bitmap) small.recycle();
            l.onColor(0xFF000000 | ((int) (r / 40) << 16) | ((int) (g / 40) << 8) | (int) (b / 40));
        } catch (RuntimeException ignored) {
        }
    }

    static MediaBackground create(Context context, FrameLayout root) {
        File base = context.getExternalFilesDir(null);
        if (base == null) return null;
        File dir = new File(base, "media");
        if (list(new File(dir, "mine")).isEmpty() && list(new File(dir, "filler")).isEmpty()) return null;
        return new MediaBackground(context, root, dir);
    }

    private MediaBackground(Context context, FrameLayout root, File mediaDir) {
        this.root = root;
        this.mediaDir = mediaDir;
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;

        holder = new FrameLayout(context);
        video = new TextureView(context);
        image = new ImageView(context);
        image.setVisibility(View.GONE);
        View scrim = new View(context);
        scrim.setBackgroundColor(SCRIM);
        ViewGroup.LayoutParams full = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        holder.addView(video, full);
        holder.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        holder.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(holder, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
                surface = new Surface(texture);
                if (pendingVideo != null && active && !closed) {
                    File file = pendingVideo;
                    int pendingValue = pendingToken;
                    pendingVideo = null;
                    startPlayer(file, pendingValue);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                releasePlayer();
                if (surface != null) {
                    surface.release();
                    surface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            }
        });
    }

    void start() {
        if (closed || active) return;
        active = true;
        if (shown == null) advance();
        else show(shown);
    }

    void stop() {
        active = false;
        handler.removeCallbacks(advanceTask);
        releasePlayer();
    }

    void releaseForMemoryPressure() {
        releasePlayer();
        image.animate().cancel();
        image.setImageDrawable(null);
        image.setVisibility(View.GONE);
        recycleCurrent();
    }

    void close() {
        active = false;
        closed = true;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        decoder.shutdownNow();
        image.animate().cancel();
        image.setImageDrawable(null);
        recycleCurrent();
        root.removeView(holder);
    }

    // ---------------------------------------------------------------- sequence

    private void advance() {
        if (!active || closed) return;
        File item = nextItem();
        if (item == null) {
            handler.postDelayed(advanceTask, SLOT_MS);   // nothing yet: look again later
            return;
        }
        show(item);
    }

    private File nextItem() {
        if (pos == 0) {
            mine = list(new File(mediaDir, "mine"));
            filler = list(new File(mediaDir, "filler"));
        }
        if (pos < mine.size()) return mine.get(pos++);
        pos = 0;
        File pick = pickFiller();
        if (pick != null) return pick;
        if (!mine.isEmpty()) {
            pos = 1;
            return mine.get(0);
        }
        return null;
    }

    private File pickFiller() {
        if (filler.isEmpty()) return null;
        List<File> candidates = new ArrayList<>(filler);
        if (candidates.size() > 1 && lastFiller != null) candidates.remove(lastFiller);
        File pick = candidates.get(random.nextInt(candidates.size()));
        lastFiller = pick;
        return pick;
    }

    private void show(File item) {
        shown = item;
        int mineToken = ++token;
        handler.removeCallbacks(advanceTask);
        handler.postDelayed(advanceTask, SLOT_MS);
        if (isVideo(item)) showVideo(item, mineToken);
        else showImage(item, mineToken);
    }

    /** A file that cannot be shown is skipped after a short pause, so a bad file never stalls the rotation. */
    private void skipSoon(int forToken) {
        if (forToken != token || !active || closed) return;
        handler.removeCallbacks(advanceTask);
        handler.postDelayed(advanceTask, 2_000L);
    }

    // ---------------------------------------------------------------- pictures

    private void showImage(File item, int forToken) {
        if (closed) return;
        final boolean fill = item.getParentFile() != null && "filler".equals(item.getParentFile().getName());
        decoder.execute(() -> {
            Bitmap bitmap = decode(item);
            handler.post(() -> {
                if (closed || forToken != token || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    skipSoon(forToken);
                    return;
                }
                Bitmap old = current;
                current = bitmap;
                image.animate().cancel();
                // Logos keep their whole frame; landscapes fill the screen.
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setImageBitmap(bitmap);
                reportColor(bitmap);
                image.setAlpha(0f);
                image.setVisibility(View.VISIBLE);
                image.animate().alpha(1f).setDuration(600).withEndAction(() -> {
                    if (forToken == token) releasePlayer();
                }).start();
                if (old != null && old != bitmap) image.postDelayed(old::recycle, 800);
            });
        });
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }

    private void recycleCurrent() {
        Bitmap old = current;
        current = null;
        if (old != null && !old.isRecycled()) old.recycle();
    }

    // ---------------------------------------------------------------- videos

    private void showVideo(File item, int forToken) {
        releasePlayer();
        if (surface == null || !surface.isValid()) {
            pendingVideo = item;
            pendingToken = forToken;
            return;
        }
        startPlayer(item, forToken);
    }

    private void startPlayer(File item, int forToken) {
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setDataSource(item.getAbsolutePath());
            mp.setSurface(surface);
            mp.setVolume(0f, 0f);
            mp.setLooping(true);
            mp.setOnPreparedListener(p -> {
                if (player == p && forToken == token) p.start();
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && forToken == token) revealVideo(forToken);
                return false;
            });
            mp.setOnErrorListener((p, what, extra) -> {
                skipSoon(forToken);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            skipSoon(forToken);
        }
    }

    /** First video frame is on screen: fade the picture that was covering it away. */
    private void revealVideo(int forToken) {
        if (image.getVisibility() != View.VISIBLE) return;
        image.animate().cancel();
        image.animate().alpha(0f).setDuration(500).withEndAction(() -> {
            if (forToken != token) return;
            image.setVisibility(View.GONE);
            image.setImageDrawable(null);
            recycleCurrent();
        }).start();
    }

    private void releasePlayer() {
        MediaPlayer old = player;
        player = null;
        pendingVideo = null;
        if (old != null) {
            try {
                old.setOnPreparedListener(null);
                old.setOnInfoListener(null);
                old.setOnErrorListener(null);
                old.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- files

    private static boolean isVideo(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4") || name.endsWith(".webm") || name.endsWith(".mkv") || name.endsWith(".mov");
    }

    private static boolean isMedia(String lower) {
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
                || lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mkv") || lower.endsWith(".mov");
    }

    static List<File> list(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return out;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (file.isFile() && file.length() > 0 && !lower.startsWith(".") && isMedia(lower)) out.add(file);
        }
        return out;
    }
}
