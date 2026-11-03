package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * First start: pick the home, CleanYourTV (the grid) or 5HC (the Google TV layout), from two pictures of them.
 * Shown by MainActivity while no style was ever chosen; the TV icon on either home changes it later.
 */
public final class HomeChooserActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    /** True until a style was chosen once (here or in Launcher settings). */
    static boolean needed(Context c) {
        return !c.getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).contains(HomeV2.KEY);
    }

    private float w;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        w = getResources().getDisplayMetrics().widthPixels;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundResource(R.drawable.settings_bg);
        root.setClipChildren(false);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        root.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        TextView title = text(getString(R.string.chooser_title), 0.024f, 0xFFFFFFFF, true);
        col.addView(title);
        TextView sub = text(getString(R.string.chooser_subtitle), 0.0115f, 0xB3FFFFFF, false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = px(0.008f);
        col.addView(sub, slp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.topMargin = px(0.04f);
        col.addView(row, rlp);

        String current = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getString(HomeV2.KEY, "v1");
        View first = card(row, R.drawable.preview_cleanyourtv, getString(R.string.home_style_v1),
                getString(R.string.chooser_v1_desc), "v1", 0);
        View second = card(row, R.drawable.preview_5hc, getString(R.string.home_style_v2),
                getString(R.string.chooser_v2_desc), "v2", px(0.045f));
        setContentView(root);
        ("v2".equals(current) ? second : first).requestFocus();
    }

    private View card(LinearLayout row, int picture, String name, String desc, String style, int leftMargin) {
        int cw = px(0.36f), ch = Math.round(cw * 9f / 16f), radius = px(0.012f), g = px(0.012f);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        card.setFocusable(true);
        card.setPadding(0, 0, 0, px(0.016f));   // room for the texts sliding down under the grown picture
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cw, -2);
        clp.leftMargin = leftMargin;
        row.addView(card, clp);

        FrameLayout frame = new FrameLayout(this);
        frame.setClipChildren(false);
        card.addView(frame, new LinearLayout.LayoutParams(cw, ch));
        // Short white glow around the focused picture.
        View shine = new View(this);
        shine.setBackground(new BitmapDrawable(getResources(), glow(cw + 2 * g, ch + 2 * g, g, radius)));
        shine.setAlpha(0f);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(cw + 2 * g, ch + 2 * g);
        sl.leftMargin = -g;
        sl.topMargin = -g;
        frame.addView(shine, sl);
        FrameLayout box = new FrameLayout(this);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        box.setClipToOutline(true);
        box.setElevation(px(0.004f));
        frame.addView(box, new FrameLayout.LayoutParams(-1, -1));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageResource(picture);
        box.addView(img, new FrameLayout.LayoutParams(-1, -1));
        View dim = new View(this);
        dim.setBackgroundColor(0x66000000);
        box.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        View ring = new View(this);
        GradientDrawable r = new GradientDrawable();
        r.setCornerRadius(radius);
        r.setStroke(Math.max(3, px(0.0022f)), 0xFFFFFFFF);
        ring.setBackground(r);
        ring.setAlpha(0f);
        box.addView(ring, new FrameLayout.LayoutParams(-1, -1));

        TextView label = text(name, 0.017f, 0xFFFFFFFF, true);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
        ll.topMargin = px(0.022f);
        card.addView(label, ll);
        TextView sub = text(desc, 0.0105f, 0xB3FFFFFF, false);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.topMargin = px(0.004f);
        card.addView(sub, dl);
        label.setAlpha(0.7f);

        card.setOnFocusChangeListener((v, f) -> {
            DecelerateInterpolator ease = new DecelerateInterpolator(1.5f);
            float s = f ? 1.06f : 1f;
            frame.animate().scaleX(s).scaleY(s).setDuration(220).setInterpolator(ease).start();
            shine.animate().alpha(f ? 1f : 0f).setDuration(220).start();
            ring.animate().alpha(f ? 1f : 0f).setDuration(200).start();
            dim.animate().alpha(f ? 0f : 1f).setDuration(220).start();
            label.animate().alpha(f ? 1f : 0.7f).translationY(f ? px(0.01f) : 0).setDuration(220).setInterpolator(ease).start();
            sub.animate().translationY(f ? px(0.01f) : 0).setDuration(220).setInterpolator(ease).start();
        });
        card.setOnClickListener(v -> choose(style));
        return card;
    }

    private void choose(String style) {
        SharedPreferences p = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        p.edit().putString(HomeV2.KEY, style)
                .putLong(Config.SETTINGS_REVISION_KEY, p.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1)
                .commit();
        // Straight to Home (closing Launcher settings too when it opened this): MainActivity is singleTask, so this
        // clears what is above it and its onResume rebuilds with the new theme.
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Back and Home keep this screen until a home is chosen; Back afterwards just closes it.
        if (keyCode == KeyEvent.KEYCODE_BACK && needed(this)) return true;
        return super.onKeyDown(keyCode, event);
    }

    private TextView text(String s, float size, int colour, boolean medium) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(colour);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * w);
        t.setTypeface(android.graphics.Typeface.create(medium ? "sans-serif-medium" : "sans-serif", android.graphics.Typeface.NORMAL));
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        return t;
    }

    private int px(float share) {
        return Math.round(share * w);
    }

    private static Bitmap glow(int w, int h, int g, float radius) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x8CFFFFFF);
        p.setMaskFilter(new BlurMaskFilter(g * 0.75f, BlurMaskFilter.Blur.NORMAL));
        float inset = g * 0.85f;
        new Canvas(b).drawRoundRect(new RectF(inset, inset, w - inset, h - inset), radius, radius, p);
        return b;
    }
}
