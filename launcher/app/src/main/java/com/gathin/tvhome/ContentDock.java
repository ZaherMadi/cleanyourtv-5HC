package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Mode simple (Home V1): the dock and the volets (YouTube, Films & séries, Sport) as one carousel of bands. The band in
 * use sits where the dock sits; its neighbours peek in at the screen's edges, smaller and dimmer, and slide in when
 * Right goes past a row's last card (or Left past its first). Up from a volet's first row selects its title: Left and
 * Right there jump from volet to volet, with arrows over the neighbours naming them. Down / Up change the volet's row.
 */
final class ContentDock {
    interface Host {
        void focusApp(int position);

        void hideAppHero();

        int lastDockApp();
    }

    private static final Object APPS = new Object();
    private static final float SIDE_SCALE = 0.86f;
    private static final float SIDE_ALPHA = 0.55f;

    private final Activity act;
    private final FrameLayout root;
    private final RecyclerView grid;
    private final DockDecoration deco;
    private final TextView appHeroName;
    private final Host host;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> arts = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final LruCache<String, Bitmap> backdrops = new LruCache<>(5);
    private final Map<String, Integer> colours = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    private final DecelerateInterpolator ease = new DecelerateInterpolator(1.6f);

    private final FrameLayout backdropLayer;
    private final ImageView backdrop;
    private final FrameLayout panel;
    private final LinearLayout heroBox, kickerRow;
    private final TextView kicker, title, meta;
    private final ImageView appIcon;
    private final LinearLayout dots;
    private final View arrowLeft, arrowRight;
    private final TextView nameLeft, nameRight;
    private final List<Page> pages = new ArrayList<>();
    /** The carousel: the apps (APPS), then each volet that has cards. */
    private final List<Object> ring = new ArrayList<>();
    private int cur;
    private float pos;
    private ValueAnimator posAnim;
    private boolean ringDirty, laidOut, peeks = true;
    private long loadedAt;
    private boolean fetching;
    private int cardW, cardH, pitch, keyline, bandLeft, bandTop, bandW, bandH, screenW;
    private float radius;
    private String backdropKey;
    private Runnable pendingBackdrop;
    /** Highlights of the focused match (YouTube), under its title. */
    private final LinearLayout strip;
    private Feeds.Card stripFor;
    private Runnable dwell;

    ContentDock(Activity act, FrameLayout root, RecyclerView grid, DockDecoration deco, TextView appHeroName, Host host) {
        this.act = act;
        this.root = root;
        this.grid = grid;
        this.deco = deco;
        this.appHeroName = appHeroName;
        this.host = host;
        density = act.getResources().getDisplayMetrics().density;

        backdropLayer = new FrameLayout(act);
        backdropLayer.setAlpha(0f);
        backdrop = new ImageView(act);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdropLayer.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(act);
        scrim.setBackground(new LayerDrawable(new Drawable[]{
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xE6000000, 0x80000000, 0x00000000}),
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x66000000, 0x1A000000, 0xF0000000}),
        }));
        backdropLayer.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        View heroScrim = root.findViewById(R.id.heroScrim);
        int index = heroScrim == null ? -1 : root.indexOfChild(heroScrim);
        root.addView(backdropLayer, Math.max(0, index), new FrameLayout.LayoutParams(-1, -1));

        panel = new FrameLayout(act);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        // Hero: the volet's title (focusable), the card's title and its line.
        heroBox = new LinearLayout(act);
        heroBox.setOrientation(LinearLayout.VERTICAL);
        heroBox.setAlpha(0f);
        heroBox.setClipChildren(false);
        kickerRow = new LinearLayout(act);
        kickerRow.setOrientation(LinearLayout.HORIZONTAL);
        kickerRow.setGravity(Gravity.CENTER_VERTICAL);
        kickerRow.setBackground(new GlassDrawable());
        kickerRow.setPadding(dp(14), dp(7), dp(10), dp(7));
        kickerRow.setFocusable(false);
        kicker = new TextView(act);
        kicker.setLetterSpacing(0.12f);
        kicker.setAllCaps(true);
        kicker.setDuplicateParentStateEnabled(true);
        kicker.setTextColor(act.getColorStateList(R.drawable.pill_text));
        kickerRow.addView(kicker);
        dots = new LinearLayout(act);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.leftMargin = dp(12);
        kickerRow.addView(dots, dl);
        heroBox.addView(kickerRow, new LinearLayout.LayoutParams(-2, -2));
        title = new TextView(act);
        title.setTextColor(0xFFFFFFFF);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = dp(8);
        heroBox.addView(title, tl);
        LinearLayout metaRow = new LinearLayout(act);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        appIcon = new ImageView(act);
        appIcon.setVisibility(View.GONE);
        appIcon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getWidth() * 0.22f);
            }
        });
        appIcon.setClipToOutline(true);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(26), dp(26));
        il.rightMargin = dp(10);
        metaRow.addView(appIcon, il);
        meta = new TextView(act);
        meta.setTextColor(0xCCFFFFFF);
        metaRow.addView(meta, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.topMargin = dp(8);
        heroBox.addView(metaRow, ml);
        strip = new LinearLayout(act);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        strip.setVisibility(View.GONE);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = dp(16);
        heroBox.addView(strip, sl);

        // Arrows over the neighbours, shown while the title is selected.
        arrowLeft = arrow(R.drawable.ic_chevron_left_v2);
        arrowRight = arrow(R.drawable.ic_chevron_right_v2);
        nameLeft = arrowName();
        nameRight = arrowName();

        kickerRow.setOnFocusChangeListener((v, f) -> {
            updateDots();
            float a = f ? 1f : 0f;
            for (View x : new View[]{arrowLeft, arrowRight, nameLeft, nameRight}) x.animate().alpha(a).setDuration(220).start();
            if (f) updateArrows();
            kickerRow.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(180).setInterpolator(ease).start();
        });
        kickerRow.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    step(-1, true);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    step(1, true);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    Object e2 = ring.isEmpty() ? APPS : ring.get(cur);
                    if (e2 instanceof Page) focusPinned(((Page) e2).currentRow(), 10);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    return true;
                default:
                    return false;
            }
        });

        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        for (Feeds.Source s : Feeds.all()) pages.add(new Page(s));
        panel.addView(heroBox, new FrameLayout.LayoutParams(-2, -2));
        panel.addView(arrowLeft);
        panel.addView(arrowRight);
        panel.addView(nameLeft);
        panel.addView(nameRight);
        ring.add(APPS);
        reload();
    }

    private View arrow(int icon) {
        FrameLayout b = new FrameLayout(act);
        b.setBackground(new GlassDrawable());
        ImageView img = new ImageView(act);
        img.setImageResource(icon);
        img.setColorFilter(0xFFFFFFFF);
        b.addView(img, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
        b.setAlpha(0f);
        b.setElevation(dp(6));
        return b;
    }

    private TextView arrowName() {
        TextView t = new TextView(act);
        t.setTextColor(0xE6FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setLetterSpacing(0.06f);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setShadowLayer(dp(6), 0, 0, 0xCC000000);
        t.setAlpha(0f);
        return t;
    }

    // ---- state ----

    boolean ready() {
        return ring.size() > 1;
    }

    /** True while a volet (not the apps) is the band in use. */
    boolean isShowing() {
        return !ring.isEmpty() && ring.get(cur) != APPS;
    }

    void onResume() {
        peeks = true;
        handler.postDelayed(() -> {
            if (isShowing()) return;
            laidOut = false;
            ensureLayout();
        }, 400);
        if (System.currentTimeMillis() - loadedAt > 10 * 60 * 1000) reload();
    }

    /** The dock opens into the full grid: the neighbours step aside. */
    void setPeeks(boolean on) {
        if (peeks == on) return;
        peeks = on;
        apply();
    }

    void destroy() {
        handler.removeCallbacksAndMessages(null);
        exec.shutdownNow();
    }

    void reload() {
        if (fetching) return;
        fetching = true;
        final Context app = act.getApplicationContext();
        final int[] left = {pages.size()};
        for (Page p : pages) {
            try {
                exec.execute(() -> {
                    List<Feeds.Section> sections = new ArrayList<>();
                    try {
                        sections = p.source.load(app);
                    } catch (Exception ignored) {
                    }
                    final List<Feeds.Section> got = sections;
                    handler.post(() -> {
                        p.set(got);
                        if (--left[0] == 0) {
                            fetching = false;
                            loadedAt = System.currentTimeMillis();
                        }
                        rebuildRing();
                        ensureLayout();
                    });
                });
            } catch (RejectedExecutionException e) {
                fetching = false;
            }
        }
    }

    private void rebuildRing() {
        if (posAnim != null && posAnim.isRunning()) {
            ringDirty = true;
            return;
        }
        Object current = ring.isEmpty() ? APPS : ring.get(cur);
        ring.clear();
        ring.add(APPS);
        for (Page p : pages) if (p.hasCards()) ring.add(p);
        cur = Math.max(0, ring.indexOf(current));
        pos = cur;
        apply();
        focusables();
    }

    // ---- carousel ----

    /** From the apps' dock edge: +1 to the next volet, -1 to the previous. False when there is none. */
    boolean show(int dir) {
        // The dock may have moved since the last time (scrolling, a new layout): copy its place again.
        laidOut = false;
        if (!ensureLayout() || ring.size() < 2) return false;
        step(dir, false);
        return true;
    }

    /** Back: straight to the apps, along the shorter way. */
    void back() {
        if (!isShowing()) return;
        int n = ring.size();
        float d = wrap(-cur, n);
        go(Math.round(d), false);
    }

    /** Home pressed: the apps at once. */
    void hideNow() {
        if (posAnim != null) posAnim.cancel();
        if (ringDirty) {
            ringDirty = false;
            rebuildRing();
        }
        cur = 0;
        pos = 0;
        apply();
        focusables();
        heroBox.animate().cancel();
        heroBox.setAlpha(0f);
        backdropLayer.animate().cancel();
        backdropLayer.setAlpha(0f);
        handler.removeCallbacks(backdropRunner);
    }

    private void step(int dir, boolean viaTitle) {
        go(dir, viaTitle);
    }

    /** Moves the carousel by steps (±1, or more for Back) and puts the focus on what arrives. */
    private void go(int steps, boolean viaTitle) {
        int n = ring.size();
        if (n < 2 || steps == 0) return;
        Object from = ring.get(cur);
        float a = pos, b = Math.round(pos) + steps;
        cur = ((Math.round(b) % n) + n) % n;
        Object to = ring.get(cur);
        animatePos(a, b, Math.abs(steps) > 1 ? 460 : 380);
        focusables();
        handler.removeCallbacks(backdropRunner);
        hideStrip();
        if (to == APPS) {
            heroBox.animate().cancel();
            heroBox.animate().alpha(0f).setDuration(200).start();
            backdropLayer.animate().cancel();
            backdropLayer.animate().alpha(0f).setDuration(320).start();
            host.focusApp(steps > 0 ? 0 : host.lastDockApp());
            return;
        }
        Page p = (Page) to;
        if (from == APPS) {
            host.hideAppHero();
            backdropLayer.animate().cancel();
            backdropLayer.animate().alpha(1f).setDuration(500).start();
        }
        heroBox.animate().cancel();
        heroBox.setAlpha(from == APPS ? 0f : 0.35f);
        heroBox.animate().alpha(1f).setDuration(320).start();
        Row r = p.currentRow();
        p.showOnly(r);
        r.view.pinned = 0;
        ((LinearLayoutManager) r.view.getLayoutManager()).scrollToPositionWithOffset(0, 0);
        updateKicker();
        if (viaTitle) {
            kickerRow.requestFocus();
            updateArrows();
            // Show the first card's picture and title while the title stays selected.
            if (!r.cards.isEmpty()) onCardFocused(p, r.cards.get(0));
        } else {
            focusPinned(r, 10);
        }
    }

    private void animatePos(float a, float b, long ms) {
        if (posAnim != null) posAnim.cancel();
        posAnim = ValueAnimator.ofFloat(a, b);
        posAnim.setDuration(ms);
        posAnim.setInterpolator(ease);
        posAnim.addUpdateListener(x -> {
            pos = (float) x.getAnimatedValue();
            apply();
        });
        posAnim.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled) return;
                pos = cur;
                apply();
                if (ringDirty) {
                    ringDirty = false;
                    rebuildRing();
                }
            }
        });
        posAnim.start();
    }

    private static float wrap(float d, int n) {
        while (d > n / 2f) d -= n;
        while (d <= -n / 2f) d += n;
        return d;
    }

    /** Places every band for the carousel position: in use where the dock is, neighbours peeking at the edges. */
    private void apply() {
        if (!laidOut) return;
        int n = ring.size();
        for (Page p : pages) if (!ring.contains(p)) p.view.setAlpha(0f);
        for (int i = 0; i < n; i++) {
            float d = n == 1 ? 0 : wrap(i - pos, n);
            Object e = ring.get(i);
            float ad = Math.abs(d), sign = d >= 0 ? 1 : -1;
            float scale = ad >= 1 ? SIDE_SCALE : 1 + (SIDE_SCALE - 1) * ad;
            // A neighbour shows its first few dp at the edge: its near edge at "peek" from the screen's side.
            float peek = Math.max(dp(18), bandLeft - dp(14));
            float centre = bandLeft + bandW / 2f;
            float sideCentre = sign > 0 ? screenW - peek + bandW * SIDE_SCALE / 2f : peek - bandW * SIDE_SCALE / 2f;
            float side = sideCentre - centre;
            float tx = ad <= 1 ? side * ad : side + sign * (ad - 1) * screenW;
            float alpha = ad <= 1 ? 1 + (SIDE_ALPHA - 1) * ad : Math.max(0, SIDE_ALPHA * (2 - ad));
            if (!peeks && ad > 0.02f) alpha = 0f;
            if (e == APPS) {
                Rect gr = new Rect(0, 0, grid.getWidth(), grid.getHeight());
                root.offsetDescendantRectToMyCoords(grid, gr);
                grid.setPivotX(centre - gr.left);
                grid.setPivotY(bandTop + bandH / 2f - gr.top);
                grid.setTranslationX(tx);
                grid.setScaleX(scale);
                grid.setScaleY(scale);
                grid.setAlpha(alpha);
            } else {
                View v = ((Page) e).view;
                v.setPivotX(bandW / 2f);
                v.setPivotY(bandH / 2f);
                v.setTranslationX(tx);
                v.setScaleX(scale);
                v.setScaleY(scale);
                v.setAlpha(alpha);
            }
        }
    }

    private void focusables() {
        boolean onApps = ring.isEmpty() || ring.get(cur) == APPS;
        grid.setDescendantFocusability(onApps ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        for (Page p : pages) {
            boolean active = !onApps && ring.get(cur) == p;
            p.view.setDescendantFocusability(active ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        kickerRow.setFocusable(!onApps);
    }

    private void updateArrows() {
        int n = ring.size();
        if (n < 2) return;
        Object l = ring.get(((cur - 1) % n + n) % n), r = ring.get((cur + 1) % n);
        nameLeft.setText(name(l));
        nameRight.setText(name(r));
    }

    private static String name(Object e) {
        return e == APPS ? "Applications" : ((Page) e).source.title();
    }

    // ---- layout ----

    /** Copies the dock band's place and the tiles' size, once the grid is laid out. */
    private boolean ensureLayout() {
        if (laidOut) return true;
        RectF b = new RectF(deco.lastBand);
        if (b.width() <= 0 || b.height() <= 0) return false;
        RecyclerView.ViewHolder first = grid.findViewHolderForAdapterPosition(0);
        View card = first == null ? null : first.itemView.findViewById(R.id.card);
        if (card == null || card.getWidth() == 0) return false;
        Rect r = new Rect(0, 0, grid.getWidth(), grid.getHeight());
        root.offsetDescendantRectToMyCoords(grid, r);
        b.offset(r.left, r.top);
        screenW = root.getWidth();
        cardW = card.getWidth();
        cardH = card.getHeight();
        int firstLeft = first.itemView.getLeft() + card.getLeft();
        RecyclerView.ViewHolder second = grid.findViewHolderForAdapterPosition(1);
        pitch = second != null && second.itemView.getTop() == first.itemView.getTop()
                ? second.itemView.getLeft() - first.itemView.getLeft() : cardW + dp(24);
        bandLeft = Math.round(b.left);
        bandTop = Math.round(b.top);
        bandW = Math.round(b.width());
        bandH = Math.round(b.height());
        keyline = Math.round(r.left + firstLeft - b.left);
        radius = deco.lastRadius;
        for (Page p : pages) p.layout();
        Rect h = new Rect(0, 0, appHeroName.getWidth(), appHeroName.getHeight());
        root.offsetDescendantRectToMyCoords(appHeroName, h);
        float big = appHeroName.getTextSize();
        title.setTypeface(appHeroName.getTypeface());
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, big * 0.78f);
        kicker.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(dp(11), big * 0.26f));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(dp(13), big * 0.34f));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(Math.round(screenW * 0.6f), -2, Gravity.START | Gravity.BOTTOM);
        hl.leftMargin = Math.max(h.left, bandLeft) - dp(14);
        hl.bottomMargin = Math.round(root.getHeight() - b.top + dp(26));
        heroBox.setLayoutParams(hl);
        int as = dp(46);
        FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(as, as);
        al.leftMargin = dp(10);
        al.topMargin = bandTop + bandH / 2 - as / 2;
        arrowLeft.setLayoutParams(al);
        FrameLayout.LayoutParams ar = new FrameLayout.LayoutParams(as, as);
        ar.leftMargin = screenW - dp(10) - as;
        ar.topMargin = bandTop + bandH / 2 - as / 2;
        arrowRight.setLayoutParams(ar);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-2, -2);
        nl.leftMargin = dp(12);
        nl.topMargin = bandTop + bandH + dp(10);
        nameLeft.setLayoutParams(nl);
        FrameLayout.LayoutParams nr = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP);
        nr.rightMargin = dp(12);
        nr.topMargin = bandTop + bandH + dp(10);
        nameRight.setLayoutParams(nr);
        laidOut = true;
        apply();
        focusables();
        return true;
    }

    // ---- rows ----

    private void switchRow(Page p, int step) {
        int next = p.current + step;
        while (next >= 0 && next < p.rows.size() && p.rows.get(next).cards.isEmpty()) next += step;
        if (next < 0 || next >= p.rows.size()) {
            if (step < 0) kickerRow.requestFocus();   // above the first row: the volet's title
            return;
        }
        Row a = p.rows.get(p.current), b = p.rows.get(next);
        float h = bandH * 0.6f;
        a.view.animate().cancel();
        a.view.animate().translationY(-step * h).alpha(0f).setDuration(200).setInterpolator(ease).withEndAction(() -> {
            a.view.setVisibility(View.INVISIBLE);
            a.view.setTranslationY(0f);
            a.view.setAlpha(1f);
        }).start();
        b.view.animate().cancel();
        b.view.setVisibility(View.VISIBLE);
        b.view.setTranslationY(step * h);
        b.view.setAlpha(0f);
        b.view.animate().translationY(0f).alpha(1f).setDuration(280).setInterpolator(ease).start();
        p.current = next;
        updateKicker();
        if (b.view.pinned == RecyclerView.NO_POSITION) b.view.pinned = 0;
        focusPinned(b, 10);
    }

    private void focusPinned(Row r, int tries) {
        if (!isShowing()) return;
        RecyclerView.ViewHolder vh = r.view.findViewHolderForAdapterPosition(Math.max(0, r.view.pinned));
        if (vh != null && vh.itemView.isLaidOut()) {
            vh.itemView.requestFocus();
        } else if (tries > 0) {
            handler.postDelayed(() -> focusPinned(r, tries - 1), 40);
        }
    }

    private void updateKicker() {
        Object e = ring.get(cur);
        if (!(e instanceof Page)) return;
        Page p = (Page) e;
        kicker.setText(p.source.title() + "  ·  " + p.currentRow().section.name);
        updateDots();
    }

    private void updateDots() {
        dots.removeAllViews();
        Object e = ring.isEmpty() ? APPS : ring.get(cur);
        if (!(e instanceof Page)) return;
        Page p = (Page) e;
        boolean dark = kickerRow.isFocused();
        for (int i = 0; i < p.rows.size(); i++) {
            if (p.rows.get(i).cards.isEmpty()) continue;
            View d = new View(act);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(i == p.current ? (dark ? 0xFF111111 : 0xFFFFFFFF) : (dark ? 0x59111111 : 0x59FFFFFF));
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
            lp.rightMargin = dp(6);
            dots.addView(d, lp);
        }
    }

    // ---- focus effects ----

    private void onCardFocused(Page p, Feeds.Card k) {
        if (dwell != null) handler.removeCallbacks(dwell);
        if (stripFor != k) hideStrip();
        if (k.kind == Feeds.SPORT) {
            dwell = () -> fetchHighlights(p, k);
            handler.postDelayed(dwell, 2000);   // two seconds on a match: its highlights come
        }
        title.setText(k.title);
        meta.setText(k.heroMeta());
        Drawable icon = null;
        if (k.iconPkg != null) {
            try {
                icon = act.getPackageManager().getApplicationIcon(k.iconPkg);
            } catch (Exception ignored) {
            }
        }
        appIcon.setImageDrawable(icon);
        appIcon.setVisibility(icon == null ? View.GONE : View.VISIBLE);
        Integer c = colours.get(key(k));
        if (c != null) p.tint(c);
        handler.removeCallbacks(backdropRunner);
        pendingBackdrop = () -> showBackdrop(p, k);
        handler.postDelayed(backdropRunner, 260);
    }

    private void hideStrip() {
        stripFor = null;
        strip.animate().cancel();
        strip.setVisibility(View.GONE);
        strip.removeAllViews();
    }

    private void fetchHighlights(Page p, Feeds.Card match) {
        if (stripFor == match) return;
        final Context app = act.getApplicationContext();
        try {
            exec.execute(() -> {
                List<Feeds.Card> videos = new ArrayList<>();
                try {
                    videos = YouTubeSource.highlights(app, match);
                } catch (Exception ignored) {
                }
                final List<Feeds.Card> got = videos;
                handler.post(() -> {
                    if (got.isEmpty() || !isShowing()) return;
                    View f = act.getCurrentFocus();
                    boolean onMatch = f != null && f.getParent() instanceof RecyclerView
                            && ((RecyclerView) f.getParent()).getChildViewHolder(f) instanceof CardHolder
                            && ((CardHolder) ((RecyclerView) f.getParent()).getChildViewHolder(f)).card == match;
                    if (onMatch) showStrip(p, match, got);
                });
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    /** "Résumés" and up to three video thumbnails under the match's title: Up from the match reaches them. */
    private void showStrip(Page p, Feeds.Card match, List<Feeds.Card> videos) {
        strip.removeAllViews();
        stripFor = match;
        TextView label = new TextView(act);
        label.setText(match.finished ? "RÉSUMÉS" : "AVANT-MATCH");
        label.setTextColor(0xB3FFFFFF);
        label.setLetterSpacing(0.14f);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, kicker.getTextSize());
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
        ll.rightMargin = dp(14);
        strip.addView(label, ll);
        int h = Math.round(cardH * 0.62f), w = Math.round(h * 16f / 9f);
        int n = Math.min(3, videos.size());
        for (int i = 0; i < n; i++) {
            final Feeds.Card v = videos.get(i);
            final int index = i;
            FrameLayout item = new FrameLayout(act);
            item.setFocusable(true);
            item.setClipChildren(false);
            FrameLayout box = new FrameLayout(act);
            final float corner = dp(8);
            box.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
                }
            });
            box.setClipToOutline(true);
            box.setElevation(dp(4));
            box.setBackgroundColor(0xFF1E1E22);
            item.addView(box, new FrameLayout.LayoutParams(w, h));
            ImageView art = new ImageView(act);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            box.addView(art, new FrameLayout.LayoutParams(-1, -1));
            View shade = new View(act);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC000000}));
            box.addView(shade, new FrameLayout.LayoutParams(-1, h / 2, Gravity.BOTTOM));
            TextView t = new TextView(act);
            t.setText(v.title);
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(dp(10), h * 0.11f));
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            tl.leftMargin = dp(8);
            tl.rightMargin = dp(8);
            tl.bottomMargin = dp(6);
            box.addView(t, tl);
            View rim = new View(act);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(corner);
            g.setStroke(Math.max(2, dp(2)), 0xFFFFFFFF);
            rim.setBackground(g);
            rim.setAlpha(0f);
            box.addView(rim, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(w, h);
            il.rightMargin = dp(14);
            strip.addView(item, il);
            item.setOnFocusChangeListener((x, f) -> {
                float sc = f ? 1.1f : 1f;
                box.animate().scaleX(sc).scaleY(sc).setDuration(200).setInterpolator(ease).start();
                rim.animate().alpha(f ? 1f : 0f).setDuration(180).start();
            });
            item.setOnClickListener(x -> YouTubeFeed.open(act, v.id));
            item.setOnKeyListener((x, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusPinned(p.currentRow(), 10);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    kickerRow.requestFocus();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && index == 0) return true;
                return code == KeyEvent.KEYCODE_DPAD_RIGHT && index == n - 1;
            });
            final Context app = act.getApplicationContext();
            try {
                exec.execute(() -> {
                    Bitmap b = YouTubeFeed.thumbnail(app, v.id, w, h);
                    if (b != null) handler.post(() -> art.setImageBitmap(b));
                });
            } catch (RejectedExecutionException ignored) {
            }
        }
        strip.setAlpha(0f);
        strip.setVisibility(View.VISIBLE);
        strip.animate().alpha(1f).setDuration(320).start();
    }

    private final Runnable backdropRunner = () -> {
        if (pendingBackdrop != null) pendingBackdrop.run();
    };

    private void showBackdrop(Page p, Feeds.Card k) {
        String key = key(k);
        if (!isShowing() || key.equals(backdropKey)) return;
        backdropKey = key;
        Bitmap cached = backdrops.get(key);
        if (cached != null) {
            crossfade(cached);
            return;
        }
        final Context app = act.getApplicationContext();
        try {
            exec.execute(() -> {
                Bitmap b = null;
                try {
                    b = p.source.backdrop(app, k);
                } catch (Exception ignored) {
                }
                final Bitmap got = b;
                handler.post(() -> {
                    if (got == null) return;
                    backdrops.put(key, got);
                    if (isShowing() && key.equals(backdropKey)) crossfade(got);
                });
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void crossfade(Bitmap b) {
        backdrop.animate().cancel();
        if (backdrop.getDrawable() == null) {
            backdrop.setImageBitmap(b);
            backdrop.setAlpha(0f);
            backdrop.animate().alpha(1f).setDuration(450).start();
            return;
        }
        backdrop.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            backdrop.setImageBitmap(b);
            backdrop.animate().alpha(1f).setDuration(420).start();
        }).start();
    }

    private static String key(Feeds.Card k) {
        return k.kind + ":" + k.id;
    }

    private void loadArt(Page p, Feeds.Card k) {
        String key = key(k);
        if (cardW == 0 || !loading.add(key)) return;
        final Context app = act.getApplicationContext();
        final int w = cardW, h = cardH;
        try {
            exec.execute(() -> {
                Bitmap b = null;
                try {
                    b = p.source.card(app, k, w, h);
                } catch (Exception ignored) {
                }
                final Bitmap got = b;
                final int col = got == null ? 0 : YouTubeFeed.colour(got);
                handler.post(() -> {
                    loading.remove(key);
                    if (got == null) return;
                    arts.put(key, got);
                    colours.put(key, col);
                    for (Row r : p.rows) {
                        for (int i = 0; i < r.view.getChildCount(); i++) {
                            RecyclerView.ViewHolder vh = r.view.getChildViewHolder(r.view.getChildAt(i));
                            if (vh instanceof CardHolder && ((CardHolder) vh).card != null && key.equals(key(((CardHolder) vh).card))) {
                                ((CardHolder) vh).art.setImageBitmap(got);
                                if (vh.itemView.isFocused()) p.tint(col);
                            }
                        }
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            loading.remove(key);
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ---- model ----

    /** A volet: its own band (background and rows), placed by the carousel. */
    private final class Page {
        final Feeds.Source source;
        final FrameLayout view;
        final GradientDrawable bandBg = new GradientDrawable();
        final FrameLayout rowsBox;
        final List<Row> rows = new ArrayList<>();
        int current;
        int bandColour;
        ValueAnimator bandAnim;

        Page(Feeds.Source source) {
            this.source = source;
            view = new FrameLayout(act);
            view.setClipChildren(false);
            view.setClipToPadding(false);
            view.setAlpha(0f);
            View band = new View(act);
            band.setBackground(bandBg);
            view.addView(band, new FrameLayout.LayoutParams(-1, -1));
            rowsBox = new FrameLayout(act);
            rowsBox.setClipChildren(false);
            rowsBox.setClipToPadding(false);
            view.addView(rowsBox, new FrameLayout.LayoutParams(-1, -1));
            view.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            panel.addView(view, 0, new FrameLayout.LayoutParams(10, 10));
        }

        void layout() {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(bandW, bandH);
            lp.leftMargin = bandLeft;
            lp.topMargin = bandTop;
            view.setLayoutParams(lp);
            bandBg.setCornerRadius(radius);
            if (bandColour == 0) {
                bandColour = deco.bandColor();
                bandBg.setColor(bandColour);
            }
            for (Row r : rows) {
                r.view.setKeyline(keyline);
                r.adapter.notifyDataSetChanged();
                for (int c = 0; c < Math.min(8, r.cards.size()); c++) loadArt(this, r.cards.get(c));
            }
            showOnly(currentRow());
        }

        void tint(int colour) {
            int target = 0xD8000000 | (Math.round(Color.red(colour) * 0.32f) << 16)
                    | (Math.round(Color.green(colour) * 0.32f) << 8) | Math.round(Color.blue(colour) * 0.32f);
            if (bandAnim != null) bandAnim.cancel();
            bandAnim = ValueAnimator.ofObject(new ArgbEvaluator(), bandColour, target);
            bandAnim.setDuration(420);
            bandAnim.addUpdateListener(a -> {
                bandColour = (int) a.getAnimatedValue();
                bandBg.setColor(bandColour);
            });
            bandAnim.start();
        }

        boolean hasCards() {
            for (Row r : rows) if (!r.cards.isEmpty()) return true;
            return false;
        }

        Row currentRow() {
            if (rows.isEmpty()) return null;
            if (current >= rows.size() || rows.get(current).cards.isEmpty()) {
                current = 0;
                for (int i = 0; i < rows.size(); i++) {
                    if (!rows.get(i).cards.isEmpty()) {
                        current = i;
                        break;
                    }
                }
            }
            return rows.get(current);
        }

        void showOnly(Row keep) {
            for (Row r : rows) {
                r.view.animate().cancel();
                r.view.setTranslationX(0f);
                r.view.setTranslationY(0f);
                r.view.setAlpha(1f);
                r.view.setVisibility(r == keep ? View.VISIBLE : View.INVISIBLE);
            }
        }

        void set(List<Feeds.Section> sections) {
            if (sections == null || sections.isEmpty()) return;
            if (isShowing() && ring.get(cur) == this) return;   // never reshuffle the volet in use
            // Themes in a random order weighted by use (RowStats): the ones used most come first more and more often.
            sections = RowStats.order(act, sections, sec -> source.title() + ":" + sec.name);
            while (rows.size() < sections.size()) rows.add(new Row(this));
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (i < sections.size()) {
                    r.section = sections.get(i);
                    r.cards = r.section.cards;
                    r.key = source.title() + ":" + r.section.name;
                } else {
                    r.cards = new ArrayList<>();
                }
                r.adapter.notifyDataSetChanged();
                r.view.pinned = 0;
                r.view.scrollToPosition(0);
                for (int c = 0; c < Math.min(8, r.cards.size()); c++) loadArt(this, r.cards.get(c));
            }
            current = 0;
            Row first = currentRow();
            if (first != null) showOnly(first);
        }
    }

    private final class Row {
        final Page page;
        final HomeV2.PinnedRow view;
        final CardAdapter adapter;
        Feeds.Section section;
        List<Feeds.Card> cards = new ArrayList<>();
        String key;

        Row(Page page) {
            this.page = page;
            view = new HomeV2.PinnedRow(act, keyline);
            adapter = new CardAdapter(this);
            view.setAdapter(adapter);
            view.setVisibility(View.INVISIBLE);
            page.rowsBox.addView(view, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    private final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        private final Row r;

        CardAdapter(Row r) {
            this.r = r;
        }

        @Override
        public int getItemCount() {
            return cardW == 0 ? 0 : r.cards.size();
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder(r);
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder h, int position) {
            Feeds.Card k = r.cards.get(position);
            h.card = k;
            h.badge.setText(k.badge);
            h.badge.setVisibility(k.badge.isEmpty() || k.kind == Feeds.SPORT ? View.GONE : View.VISIBLE);
            GradientDrawable bb = new GradientDrawable();
            bb.setCornerRadius(dp(3));
            bb.setColor(k.badgeColour);
            h.badge.setBackground(bb);
            Bitmap b = arts.get(key(k));
            if (b != null) h.art.setImageBitmap(b);
            else {
                h.art.setImageDrawable(null);
                loadArt(r.page, k);
            }
            h.setFocused(h.itemView.isFocused(), false);
        }
    }

    private final class CardHolder extends RecyclerView.ViewHolder {
        Feeds.Card card;
        final FrameLayout box;
        final ImageView art;
        final View dim, rim;
        final TextView badge;

        CardHolder(Row r) {
            super(new FrameLayout(act));
            FrameLayout item = (FrameLayout) itemView;
            item.setLayoutParams(new RecyclerView.LayoutParams(pitch, -1));
            item.setClipChildren(false);
            item.setFocusable(true);
            box = new FrameLayout(act);
            final float corner = AppAdapter.cornerRadius(cardW, density);
            box.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), corner);
                }
            });
            box.setClipToOutline(true);
            box.setElevation(5f * density);
            box.setBackgroundColor(0xFF1E1E22);
            item.addView(box, new FrameLayout.LayoutParams(cardW, cardH, Gravity.START | Gravity.CENTER_VERTICAL));
            art = new ImageView(act);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            box.addView(art, new FrameLayout.LayoutParams(-1, -1));
            badge = new TextView(act);
            badge.setTextColor(0xFFFFFFFF);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(dp(10), cardH * 0.085f));
            badge.setPadding(dp(5), dp(1.5f), dp(5), dp(1.5f));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
            bl.rightMargin = dp(6);
            bl.bottomMargin = dp(6);
            box.addView(badge, bl);
            dim = new View(act);
            dim.setBackgroundColor(0x33000000);
            box.addView(dim, new FrameLayout.LayoutParams(-1, -1));
            rim = new View(act);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(corner);
            g.setStroke(Math.max(2, dp(2)), 0xD9FFFFFF);
            rim.setBackground(g);
            rim.setAlpha(0f);
            box.addView(rim, new FrameLayout.LayoutParams(-1, -1));
            item.setOnClickListener(v -> {
                if (card == null) return;
                if (r.key != null) RowStats.open(act, r.key);
                if (card.kind == Feeds.SPORT) {
                    fetchHighlights(r.page, card);
                    return;
                }
                r.page.source.open(act, card);
            });
            item.setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (pos == 0) {
                            if (e.getRepeatCount() == 0) step(-1, false);
                            return true;
                        }
                        return false;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (pos == r.cards.size() - 1) {
                            if (e.getRepeatCount() == 0) step(1, false);
                            return true;
                        }
                        return false;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (e.getRepeatCount() == 0) switchRow(r.page, 1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (stripFor == card && strip.getVisibility() == View.VISIBLE && strip.getChildCount() > 1) {
                            strip.getChildAt(1).requestFocus();
                            return true;
                        }
                        if (e.getRepeatCount() == 0) switchRow(r.page, -1);
                        return true;
                    default:
                        return false;
                }
            });
            item.setOnFocusChangeListener((v, f) -> {
                setFocused(f, true);
                if (f && card != null) {
                    if (r.key != null) RowStats.visit(act, r.key);
                    r.view.pin(v);
                    onCardFocused(r.page, card);
                }
            });
        }

        void setFocused(boolean f, boolean animate) {
            float s = f ? 1.12f : 1f;
            float z = f ? 22f * density : 0f;
            if (!animate) {
                box.setScaleX(s);
                box.setScaleY(s);
                box.setTranslationZ(z);
                rim.setAlpha(f ? 1f : 0f);
                dim.setAlpha(f ? 0f : 1f);
                return;
            }
            box.animate().scaleX(s).scaleY(s).translationZ(z).setDuration(220).setInterpolator(ease).start();
            rim.animate().alpha(f ? 1f : 0f).setDuration(200).start();
            dim.animate().alpha(f ? 0f : 1f).setDuration(220).start();
        }
    }
}
