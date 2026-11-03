package com.gathin.tvhome;

import android.graphics.Color;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.VideoView;

import java.io.File;

/**
 * Screensaver: media/idle.mp4 in the app's files folder, played full screen, muted, in a loop.
 * Black when the file is missing. No network.
 */
public class VideoDream extends DreamService {
    static final String FILE_NAME = "idle.mp4";

    private VideoView video;
    private boolean ready;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(true);
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        layout.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        setContentView(layout);
        File base = getExternalFilesDir(null);
        File file = base == null ? null : new File(new File(base, "media"), FILE_NAME);
        if (file != null && file.isFile()) {
            video.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                mp.setVolume(0f, 0f);
            });
            video.setOnErrorListener((mp, what, extra) -> true);   // stay black rather than show an error dialog
            video.setVideoPath(file.getAbsolutePath());
            ready = true;
        }
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (ready && video != null) video.start();
    }

    @Override
    public void onDreamingStopped() {
        if (video != null) video.stopPlayback();
        super.onDreamingStopped();
    }
}
