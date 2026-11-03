package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

/**
 * "Liquid glass" pill: translucent body, bright specular highlight on the upper half, soft shade at the bottom and a
 * rim that is lit from above. Focus fades it towards a brighter, more opaque glass; activated tints it blue.
 * Inflated from XML via {@code <drawable class="...GlassDrawable"/>}, so it needs a public no-arg constructor.
 */
public class GlassDrawable extends Drawable {
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spec = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float density = android.content.res.Resources.getSystem().getDisplayMetrics().density;
    private float focus;      // 0..1
    private boolean activated;
    private ValueAnimator anim;

    public GlassDrawable() {
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, density));
        tint.setColor(0x596EC1FF);
    }

    @Override
    protected void onBoundsChange(android.graphics.Rect b) {
        float h = b.height();
        body.setShader(new LinearGradient(0, b.top, 0, b.bottom, 0x38FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        spec.setShader(new LinearGradient(0, b.top, 0, b.top + h * 0.55f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        shade.setShader(new LinearGradient(0, b.top + h * 0.55f, 0, b.bottom, 0x00000000, 0x33000000, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(0, b.top, 0, b.bottom, 0xB3FFFFFF, 0x33FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas c) {
        r.set(getBounds());
        float half = rim.getStrokeWidth() / 2f;
        r.inset(half, half);
        float rad = r.height() / 2f;
        body.setAlpha(255);
        c.drawRoundRect(r, rad, rad, body);
        if (activated) c.drawRoundRect(r, rad, rad, tint);
        c.drawRoundRect(r, rad, rad, shade);
        if (focus > 0f) {
            // Brighter glass when focused: a white veil plus a stronger highlight.
            Paint veil = spec;
            int save = c.save();
            c.clipRect(r.left, r.top, r.right, r.top + r.height() * 0.55f);
            spec.setAlpha(255);
            c.drawRoundRect(r, rad, rad, spec);
            c.restoreToCount(save);
            tint.setAlpha(255);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFFFFF);
            p.setAlpha((int) (200 * focus));
            c.drawRoundRect(r, rad, rad, p);
        } else {
            int save = c.save();
            c.clipRect(r.left, r.top, r.right, r.top + r.height() * 0.55f);
            c.drawRoundRect(r, rad, rad, spec);
            c.restoreToCount(save);
        }
        rim.setAlpha(255);
        c.drawRoundRect(r, rad, rad, rim);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean f = false, a = false;
        for (int s : state) {
            if (s == android.R.attr.state_focused) f = true;
            else if (s == android.R.attr.state_activated) a = true;
        }
        boolean changed = a != activated;
        activated = a;
        float target = f ? 1f : 0f;
        if (target != focus) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(focus, target);
            anim.setDuration(180);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(v -> {
                focus = (float) v.getAnimatedValue();
                invalidateSelf();
            });
            anim.start();
            changed = true;
        }
        return changed;
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
