package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.VideoView;

import java.io.File;

/**
 * The video played once after each boot, with its sound: media/intro.mp4 in the app's files folder.
 * A key skips it, but never before MIN_SKIP_MS: pressed earlier, the video plays on and is cut at that point.
 * Volume keys are left to the system. Rename or remove the file to switch the intro off.
 */
public class IntroActivity extends Activity {
    static final String FILE_NAME = "intro.mp4";
    /** The logo is fully formed by then; a key pressed sooner takes effect here, not before. */
    private static final int MIN_SKIP_MS = 11_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout layout;
    private VideoView video;
    private boolean finished;
    private boolean skipRequested;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (finished) return;
            if (skipRequested && position() >= MIN_SKIP_MS) {
                fadeOut();
                return;
            }
            handler.postDelayed(this, 100);
        }
    };

    static File file(Context context) {
        File base = context.getExternalFilesDir(null);
        return base == null ? null : new File(new File(base, "media"), FILE_NAME);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        layout.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        setContentView(layout);
        File file = file(this);
        if (file == null || !file.isFile()) {
            end();
            return;
        }
        video.setOnCompletionListener(mp -> end());
        video.setOnErrorListener((mp, what, extra) -> {
            end();
            return true;
        });
        video.setVideoPath(file.getAbsolutePath());
        video.start();
        handler.postDelayed(poll, 100);
    }

    private int position() {
        try {
            return video == null ? 0 : video.getCurrentPosition();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.onKeyDown(keyCode, event);
        }
        if (event.getRepeatCount() == 0) {
            if (position() >= MIN_SKIP_MS) fadeOut();
            else skipRequested = true;   // the poll cuts the video when it reaches MIN_SKIP_MS
        }
        return true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        end();   // another app came to the front: nothing to go back to
    }

    private void fadeOut() {
        if (finished) return;
        handler.removeCallbacks(poll);
        layout.animate().alpha(0f).setDuration(300).withEndAction(this::end).start();
    }

    private void end() {
        if (finished) return;
        finished = true;
        handler.removeCallbacks(poll);
        if (video != null) video.stopPlayback();
        finish();
        overridePendingTransition(0, 0);
    }
}
