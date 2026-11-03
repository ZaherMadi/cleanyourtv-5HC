package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Home "V2", laid out after Google TV's home: avatar, tabs, search / settings and the time on top, then "Your apps"
 * as round icons in an endless row whose focused app stays on the left key line, then the apps used last as wide
 * cards. A dark background takes a soft glow of the focused app's colour. Built in code over MainActivity's root,
 * which keeps running underneath for the intro, the screensaver, packages and the remote keys.
 * Sizes are in "u", 1/874 of the screen width: the unit of the reference screenshot, so the proportions match it.
 */
final class HomeV2 {
    static final String KEY = "home_style";

    static boolean enabled(Context c) {
        return "v2".equals(c.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString(KEY, "v1"));
    }

    /** {@code am start ... --es home_style v2|v1}: switch from ADB. True when the style changed. */
    static boolean applyExtra(Context c, Intent i) {
        String v = i == null ? null : i.getStringExtra(KEY);
        if (!"v1".equals(v) && !"v2".equals(v)) return false;
        i.removeExtra(KEY);
        SharedPreferences p = c.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        if (v.equals(p.getString(KEY, "v1"))) return false;
        p.edit().putString(KEY, v).commit();
        return true;
    }

    interface Actions {
        void openSettings();
        void openProfile();
        void openSearch();
    }

    private static final int REPEAT = 2000;
    private static final int TEXT = 0xFFFFFFFF;

    private final Activity act;
    private final AppAdapter apps;
    private final Actions actions;
    private final float u;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Glow glow;
    private final Scroller scroll;
    private final LinearLayout content;
    private final Gate bar;
    private final TextView[] tabs = new TextView[4];
    private int selectedTab;
    private final TextView clock;
    private final TextView appsTitle;
    private final PinnedRow appsRow;
    private final CircleAdapter circleAdapter = new CircleAdapter();
    private final TextView recentTitle;
    private final PinnedRow recentRow;
    private final CardAdapter cardAdapter = new CardAdapter();
    private final List<AppAdapter.Entry> entries = new ArrayList<>();
    private final List<Integer> colours = new ArrayList<>();
    private final List<AppAdapter.Entry> recents = new ArrayList<>();
    private final Map<String, Bitmap> circleCache = new HashMap<>();
    private final Map<String, Bitmap> cardCache = new HashMap<>();
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private String signature = "";
    // ---- online rows: YouTube, films & séries, sport ----
    private final java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newFixedThreadPool(3);
    private final android.util.LruCache<String, Bitmap> arts = new android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Map<String, Integer> artColours = new HashMap<>();
    private final java.util.Set<String> artLoading = new java.util.HashSet<>();
    private final List<FeedGroup> groups = new ArrayList<>();
    private long feedsLoadedAt;
    private boolean feedsLoading;
    private TextView weather;
    private LinearLayout feedsBox;
    /** A match's highlights (YouTube), slipped in just under its row. */
    private FeedRow highlightRow;
    private Feeds.Card highlightFor;
    private Runnable dwell;

    HomeV2(Activity act, FrameLayout root, AppAdapter apps, Actions actions) {
        this.act = act;
        this.apps = apps;
        this.actions = actions;
        u = act.getResources().getDisplayMetrics().widthPixels / 874f;

        // Hide Home V1 underneath and keep its views out of focus.
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            c.setVisibility(View.GONE);
            c.setFocusable(false);
            if (c instanceof ViewGroup) ((ViewGroup) c).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }

        FrameLayout view = new FrameLayout(act);
        view.setBackgroundColor(0xFF0E0B0D);
        glow = new Glow(act);
        view.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        scroll = new Scroller(act);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(0, 0, 0, px(60));
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));
        view.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        // ---- top bar ----
        bar = new Gate(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(36), 0, px(36), 0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, px(40));
        barLp.topMargin = px(36);
        content.addView(bar, barLp);

        FrameLayout avatar = roundButton(R.drawable.ic_tv_v2, px(28), px(17));
        avatar.setContentDescription(act.getString(R.string.v2_profile));
        avatar.setOnClickListener(v -> actions.openProfile());
        bar.addView(avatar);

        String[] names = {act.getString(R.string.v2_for_you), act.getString(R.string.v2_live),
                act.getString(R.string.v2_apps), act.getString(R.string.v2_library)};
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            TextView t = new TextView(act);
            t.setText(names[i]);
            t.setTypeface(medium);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * u);
            t.setGravity(Gravity.CENTER);
            t.setPadding(px(14), 0, px(14), 0);
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, f) -> {
                if (f) {
                    selectTab(index);
                    scrollTop();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(32));
            lp.leftMargin = i == 0 ? px(22) : px(2);
            bar.addView(t, lp);
            tabs[i] = t;
        }
        bar.preferred = tabs[0];
        selectTab(0);

        View spacer = new View(act);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        FrameLayout search = roundButton(R.drawable.ic_search_v2, px(30), px(16));
        search.setContentDescription(act.getString(R.string.v2_search));
        search.setOnClickListener(v -> actions.openSearch());
        bar.addView(search);
        FrameLayout settings = roundButton(R.drawable.ic_settings, px(30), px(16));
        settings.setContentDescription(act.getString(R.string.v2_settings));
        settings.setOnClickListener(v -> actions.openSettings());
        ((LinearLayout.LayoutParams) settings.getLayoutParams()).leftMargin = px(12);
        bar.addView(settings);
        weather = new TextView(act);
        weather.setTextColor(0xE6FFFFFF);
        weather.setTypeface(regular);
        weather.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * u);
        weather.setVisibility(View.GONE);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, -2);
        wl.leftMargin = px(16);
        bar.addView(weather, wl);
        WeatherFeed.into(weather);
        clock = new TextView(act);
        clock.setTextColor(TEXT);
        clock.setTypeface(regular);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * u);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = px(14);
        bar.addView(clock, clp);

        // ---- your apps ----
        appsTitle = heading(R.string.v2_your_apps, 30);
        appsRow = new PinnedRow(act, px(36));
        appsRow.setAdapter(circleAdapter);
        content.addView(appsRow, new LinearLayout.LayoutParams(-1, -2));

        // ---- recently used ----
        recentTitle = heading(R.string.v2_recent, 8);
        recentRow = new PinnedRow(act, px(36));
        recentRow.setAdapter(cardAdapter);
        content.addView(recentRow, new LinearLayout.LayoutParams(-1, -2));

        // ---- YouTube, films & séries, sport: all their rows in one block, in a random order weighted by use ----
        feedsBox = new LinearLayout(act);
        feedsBox.setOrientation(LinearLayout.VERTICAL);
        feedsBox.setClipChildren(false);
        feedsBox.setClipToPadding(false);
        content.addView(feedsBox, new LinearLayout.LayoutParams(-1, -2));
        for (Feeds.Source src : Feeds.all()) groups.add(new FeedGroup(src));

        root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        refresh();
        reloadYouTube();
        tick.run();
        appsRow.post(this::focusApps);
    }

    // ---- lifecycle ----

    void onResume() {
        refresh();
        if (System.currentTimeMillis() - feedsLoadedAt > 10 * 60 * 1000) reloadYouTube();
        if (weather != null) WeatherFeed.into(weather);
        orderFeeds();
        if (act.getCurrentFocus() == null || !scroll.hasFocus()) appsRow.post(this::focusApps);
    }

    /** Back: up to the first row, the way Google TV goes back to the top. */
    void onBack() {
        scrollTop();
        focusApps();
    }

    boolean dispatchKey(KeyEvent e) {
        return false;
    }

    /** Re-reads the app list from Home's adapter (only rebinds when it changed) and the recently used apps. */
    void refresh() {
        List<AppAdapter.Entry> fresh = new ArrayList<>();
        List<Integer> freshColours = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < apps.appCount(); i++) {
            AppAdapter.Entry e = apps.entryAt(i);
            if (e.kind != AppAdapter.Entry.APP && e.kind != AppAdapter.Entry.CARD) continue;
            fresh.add(e);
            freshColours.add(apps.ambientColorAt(i));
            sig.append(e.component.flattenToShortString()).append('|');
        }
        if (!sig.toString().equals(signature)) {
            signature = sig.toString();
            circleCache.clear();
            cardCache.clear();
            entries.clear();
            entries.addAll(fresh);
            colours.clear();
            colours.addAll(freshColours);
            circleAdapter.notifyDataSetChanged();
            int start = entries.isEmpty() ? 0 : entries.size() * (REPEAT / 2);
            appsRow.pinned = start;
            ((LinearLayoutManager) appsRow.getLayoutManager()).scrollToPositionWithOffset(start, 0);
        }
        loadRecents();
    }

    private void loadRecents() {
        Map<String, Long> last = new HashMap<>();
        try {
            UsageStatsManager usm = (UsageStatsManager) act.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm == null ? null
                    : usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 30L * 24 * 3600 * 1000, now);
            if (stats != null) for (UsageStats s : stats) {
                if (s.getTotalTimeInForeground() <= 0) continue;
                Long old = last.get(s.getPackageName());
                if (old == null || s.getLastTimeUsed() > old) last.put(s.getPackageName(), s.getLastTimeUsed());
            }
        } catch (RuntimeException ignored) {
            // no usage access: the launcher's own record below
        }
        List<AppAdapter.Entry> list = new ArrayList<>();
        Map<AppAdapter.Entry, Long> when = new HashMap<>();
        for (AppAdapter.Entry e : entries) {
            long t = Math.max(LastOpened.get(act, e.pkg), last.containsKey(e.pkg) ? last.get(e.pkg) : 0L);
            if (t <= 0 || e.pkg.equals(act.getPackageName())) continue;
            when.put(e, t);
            list.add(e);
        }
        Collections.sort(list, (a, b) -> Long.compare(when.get(b), when.get(a)));
        if (list.size() > 10) list = new ArrayList<>(list.subList(0, 10));
        boolean same = list.equals(recents);
        if (!same) {
            boolean hadFocus = recentRow.hasFocus();
            recents.clear();
            recents.addAll(list);
            cardAdapter.notifyDataSetChanged();
            recentRow.pinned = 0;
            recentRow.scrollToPosition(0);
            if (hadFocus) recentRow.post(() -> recentRow.requestFocus());
        }
        int vis = recents.isEmpty() ? View.GONE : View.VISIBLE;
        recentTitle.setVisibility(vis);
        recentRow.setVisibility(vis);
    }

    private void focusApps() {
        if (entries.isEmpty()) {
            tabs[selectedTab].requestFocus();
            return;
        }
        RecyclerView.ViewHolder vh = appsRow.findViewHolderForAdapterPosition(appsRow.pinned);
        if (vh != null) vh.itemView.requestFocus();
        else appsRow.post(() -> {
            RecyclerView.ViewHolder again = appsRow.findViewHolderForAdapterPosition(appsRow.pinned);
            if (again != null) again.itemView.requestFocus();
        });
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clock.setText(android.text.format.DateFormat.getTimeFormat(act).format(new Date()));
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000 - now % 60_000 + 50);
        }
    };

    void destroy() {
        handler.removeCallbacksAndMessages(null);
        exec.shutdownNow();
    }

    /** Loads every online row (cached lists while fresh) in the background; rows without cards stay hidden. */
    void reloadYouTube() {
        if (feedsLoading) return;
        feedsLoading = true;
        final Context app = act.getApplicationContext();
        final int[] left = {groups.size()};
        for (FeedGroup g : groups) {
            try {
                exec.execute(() -> {
                    List<Feeds.Section> got = new ArrayList<>();
                    try {
                        got = g.source.load(app);
                    } catch (Exception ignored) {
                    }
                    final List<Feeds.Section> sections = got;
                    handler.post(() -> {
                        g.set(sections);
                        if (--left[0] == 0) {
                            feedsLoading = false;
                            feedsLoadedAt = System.currentTimeMillis();
                        }
                    });
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                feedsLoading = false;
            }
        }
    }

    // ---- pieces ----

    private int px(float units) {
        return Math.round(units * u);
    }

    private TextView heading(int text, float top) {
        return heading(act.getString(text), top);
    }

    private TextView heading(CharSequence text, float top) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTypeface(regular);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * u);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(36);
        lp.topMargin = px(top);
        content.addView(t, lp);
        return t;
    }

    private void selectTab(int index) {
        selectedTab = index;
        bar.preferred = tabs[index];
        for (int i = 0; i < tabs.length; i++) {
            boolean on = i == index;
            if (on) {
                GradientDrawable pill = new GradientDrawable();
                pill.setColor(0xFFFFFFFF);
                pill.setCornerRadius(16f * u);
                tabs[i].setBackground(pill);
            } else {
                tabs[i].setBackground(null);
            }
            tabs[i].setTextColor(on ? 0xFF111111 : 0xE6FFFFFF);
        }
    }

    /** A round liquid-glass button with an icon that turns dark on the bright focused glass. */
    private FrameLayout roundButton(int icon, int size, int iconSize) {
        FrameLayout b = new FrameLayout(act);
        b.setBackground(new GlassDrawable());
        b.setFocusable(true);
        ImageView img = new ImageView(act);
        img.setImageResource(icon);
        img.setColorFilter(TEXT);
        b.addView(img, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
        b.setOnFocusChangeListener((v, f) -> {
            img.setColorFilter(f ? 0xFF111111 : TEXT);
            v.animate().scaleX(f ? 1.1f : 1f).scaleY(f ? 1.1f : 1f).setDuration(160)
                    .setInterpolator(new DecelerateInterpolator()).start();
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return b;
    }

    private void scrollTop() {
        scroll.smoothScrollTo(0, 0);
    }

    /** Scrolls so the row's heading sits near the top, as Google TV does row by row. */
    private void scrollToRow(View title) {
        if (title == appsTitle) {
            scrollTop();
            return;
        }
        Rect r = new Rect();
        title.getDrawingRect(r);
        content.offsetDescendantRectToMyCoords(title, r);
        scroll.smoothScrollTo(0, Math.max(0, r.top - px(60)));
    }

    private void open(AppAdapter.Entry e) {
        LastOpened.record(act, e.pkg);
        apps.open(e);
    }

    // ---- icons ----

    private Drawable iconOf(AppAdapter.Entry e) {
        PackageManager pm = act.getPackageManager();
        try {
            return pm.getActivityIcon(e.component);
        } catch (Exception ignored) {
        }
        try {
            return pm.getApplicationIcon(e.pkg);
        } catch (Exception ignored) {
        }
        return e.icon;
    }

    /** The app's icon filling a circle: adaptive icons full bleed, legacy ones on their own edge colour. */
    private Bitmap circle(AppAdapter.Entry e, int size) {
        String key = e.pkg + "@" + size;
        Bitmap cached = circleCache.get(key);
        if (cached != null) return cached;
        Bitmap art = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(art);
        Drawable d = iconOf(e);
        if (d instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
            int ext = size / 4;
            Drawable back = a.getBackground(), fore = a.getForeground();
            if (back != null) {
                back = back.mutate();
                back.setBounds(-ext, -ext, size + ext, size + ext);
                back.draw(c);
            } else {
                c.drawColor(0xFFFFFFFF);
            }
            if (fore != null) {
                fore = fore.mutate();
                fore.setBounds(-ext, -ext, size + ext, size + ext);
                fore.draw(c);
            }
        } else if (d != null) {
            d = d.mutate();
            Bitmap probe = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 32, 32);
            d.draw(new Canvas(probe));
            boolean fullBleed = Color.alpha(probe.getPixel(1, 1)) > 200 && Color.alpha(probe.getPixel(30, 30)) > 200;
            int edge = probe.getPixel(16, 2);
            if (Color.alpha(edge) < 200) edge = probe.getPixel(16, 6);
            c.drawColor(Color.alpha(edge) > 200 ? (edge | 0xFF000000) : 0xFFFFFFFF);
            int inset = fullBleed ? 0 : Math.round(size * 0.08f);
            d.setBounds(inset, inset, size - inset, size - inset);
            d.draw(c);
        }
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, p);
        art.recycle();
        circleCache.put(key, out);
        return out;
    }

    /** A wide card: the app's TV banner when it has one, else its icon on a gradient of its colour. */
    private Bitmap card(AppAdapter.Entry e, int w, int h, int colour) {
        Bitmap cached = cardCache.get(e.pkg);
        if (cached != null) return cached;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Drawable banner = null;
        PackageManager pm = act.getPackageManager();
        try {
            banner = pm.getActivityBanner(e.component);
        } catch (Exception ignored) {
        }
        if (banner == null) {
            try {
                banner = pm.getApplicationBanner(e.pkg);
            } catch (Exception ignored) {
            }
        }
        if (banner != null) {
            banner = banner.mutate();
            int bw = Math.max(1, banner.getIntrinsicWidth()), bh = Math.max(1, banner.getIntrinsicHeight());
            float s = Math.max(w / (float) bw, h / (float) bh);
            int dw = Math.round(bw * s), dh = Math.round(bh * s);
            banner.setBounds((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
            banner.draw(c);
        } else {
            Paint g = new Paint();
            g.setShader(new LinearGradient(0, 0, w, h, mix(colour, 0xFF000000, 0.25f), mix(colour, 0xFF000000, 0.7f),
                    Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, g);
            int d = Math.round(h * 0.5f);
            Bitmap icon = circle(e, d);
            c.drawBitmap(icon, (w - d) / 2f, (h - d) / 2f, new Paint(Paint.FILTER_BITMAP_FLAG));
        }
        cardCache.put(e.pkg, out);
        return out;
    }

    /** Width of the cards' white glow, in u: short, just a soft light around the edge. */
    private static final float GLOW = 7f;
    private Bitmap cardGlow;

    private Bitmap cardGlow() {
        if (cardGlow != null) return cardGlow;
        int g = px(GLOW), w = px(186) + 2 * g, h = px(104) + 2 * g;
        cardGlow = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x8CFFFFFF);
        p.setMaskFilter(new android.graphics.BlurMaskFilter(g * 0.75f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        float inset = g * 0.85f;
        new Canvas(cardGlow).drawRoundRect(new android.graphics.RectF(inset, inset, w - inset, h - inset), 7f * u, 7f * u, p);
        return cardGlow;
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1 - t) + Color.green(b) * t),
                Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    private int colourOf(AppAdapter.Entry e) {
        int i = entries.indexOf(e);
        return i >= 0 && i < colours.size() ? colours.get(i) : 0xFF555555;
    }

    // ---- "Your apps": round icons, endless ----

    private final class CircleAdapter extends RecyclerView.Adapter<CircleHolder> {
        @Override
        public int getItemCount() {
            return entries.isEmpty() ? 0 : entries.size() * REPEAT;
        }

        @NonNull
        @Override
        public CircleHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CircleHolder();
        }

        @Override
        public void onBindViewHolder(@NonNull CircleHolder h, int position) {
            AppAdapter.Entry e = entries.get(position % entries.size());
            int colour = colours.get(position % entries.size());
            h.entry = e;
            h.colour = colour;
            h.icon.setImageBitmap(circle(e, px(72 * 1.15f)));
            h.label.setText(e.label);
            GradientDrawable halo = new GradientDrawable();
            halo.setShape(GradientDrawable.OVAL);
            halo.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            halo.setGradientRadius(px(56));
            int c = colour & 0x00FFFFFF;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                halo.setColors(new int[]{0xE0000000 | c, 0x99000000 | c, c}, new float[]{0f, 0.72f, 1f});
            } else {
                halo.setColors(new int[]{0xE0000000 | c, 0x66000000 | c, c});
            }
            h.halo.setBackground(halo);
            h.setFocused(h.itemView.isFocused(), false);
        }
    }

    private final class CircleHolder extends RecyclerView.ViewHolder {
        AppAdapter.Entry entry;
        int colour;
        final FrameLayout box;
        final ImageView icon;
        final View dim, ring, halo;
        final TextView label;

        CircleHolder() {
            super(new FrameLayout(act));
            FrameLayout item = (FrameLayout) itemView;
            item.setLayoutParams(new RecyclerView.LayoutParams(px(91), -2));
            item.setClipChildren(false);
            item.setFocusable(true);
            int d = px(72);
            halo = new View(act);
            halo.setAlpha(0f);
            FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(px(112), px(112), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            hl.topMargin = px(10) + d / 2 - px(56);
            item.addView(halo, hl);
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setClipChildren(false);
            item.addView(col, new FrameLayout.LayoutParams(-1, -2));
            box = new FrameLayout(act);
            box.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            box.setClipToOutline(true);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(d, d);
            bl.topMargin = px(10);
            col.addView(box, bl);
            icon = new ImageView(act);
            icon.setScaleType(ImageView.ScaleType.FIT_XY);
            box.addView(icon, new FrameLayout.LayoutParams(-1, -1));
            dim = new View(act);
            dim.setBackgroundColor(0x33000000);
            box.addView(dim, new FrameLayout.LayoutParams(-1, -1));
            ring = new View(act);
            GradientDrawable r = new GradientDrawable();
            r.setShape(GradientDrawable.OVAL);
            r.setStroke(Math.max(2, px(2.2f)), 0xFFFFFFFF);
            ring.setBackground(r);
            ring.setAlpha(0f);
            box.addView(ring, new FrameLayout.LayoutParams(-1, -1));
            label = new TextView(act);
            label.setTextColor(TEXT);
            label.setTypeface(regular);
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, 11.5f * u);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setAlpha(0.72f);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
            ll.topMargin = px(8);
            ll.leftMargin = px(3);
            ll.rightMargin = px(3);
            col.addView(label, ll);
            // Room under the labels for two lines, so rows keep one height.
            label.setMinLines(2);
            col.setPadding(0, 0, 0, px(6));
            item.setOnClickListener(v -> {
                if (entry != null) open(entry);
            });
            item.setOnFocusChangeListener((v, f) -> {
                setFocused(f, true);
                if (f) {
                    appsRow.pin(v);
                    glow.setColour(colour);
                    scrollToRow(appsTitle);
                }
            });
        }

        void setFocused(boolean f, boolean animate) {
            float s = f ? 1.15f : 1f;
            if (!animate) {
                box.setScaleX(s);
                box.setScaleY(s);
                halo.setAlpha(f ? 1f : 0f);
                ring.setAlpha(f ? 1f : 0f);
                dim.setAlpha(f ? 0f : 1f);
                label.setAlpha(f ? 1f : 0.72f);
                label.setTranslationY(f ? px(5) : 0);
                return;
            }
            DecelerateInterpolator ease = new DecelerateInterpolator(1.5f);
            box.animate().scaleX(s).scaleY(s).setDuration(200).setInterpolator(ease).start();
            halo.animate().alpha(f ? 1f : 0f).setDuration(220).setInterpolator(ease).start();
            ring.animate().alpha(f ? 1f : 0f).setDuration(180).start();
            dim.animate().alpha(f ? 0f : 1f).setDuration(200).start();
            label.animate().alpha(f ? 1f : 0.72f).translationY(f ? px(5) : 0).setDuration(200).setInterpolator(ease).start();
        }
    }

    // ---- recently used: wide cards ----

    private final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        @Override
        public int getItemCount() {
            return recents.size();
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder();
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder h, int position) {
            AppAdapter.Entry e = recents.get(position);
            h.entry = e;
            h.colour = colourOf(e);
            h.art.setImageBitmap(card(e, px(186), px(104), h.colour));
            h.setFocused(h.itemView.isFocused(), false);
        }
    }

    private final class CardHolder extends RecyclerView.ViewHolder {
        AppAdapter.Entry entry;
        int colour;
        final FrameLayout box;
        final ImageView art;
        final View dim, ring, shine;

        CardHolder() {
            super(new FrameLayout(act));
            FrameLayout item = (FrameLayout) itemView;
            item.setLayoutParams(new RecyclerView.LayoutParams(px(204), -2));
            item.setClipChildren(false);
            item.setFocusable(true);
            item.setPadding(0, px(10), 0, px(12));
            item.setClipToPadding(false);   // the focused card grows past the padding
            // A short white glow around the focused card, drawn once and shared.
            shine = new View(act);
            shine.setBackground(new android.graphics.drawable.BitmapDrawable(act.getResources(), cardGlow()));
            shine.setAlpha(0f);
            int g = px(GLOW);
            FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(px(186) + 2 * g, px(104) + 2 * g, Gravity.START | Gravity.TOP);
            gl.leftMargin = -g;
            gl.topMargin = -g;
            item.addView(shine, gl);
            box = new FrameLayout(act);
            final float radius = 7f * u;
            box.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            box.setClipToOutline(true);
            box.setElevation(3f * u);
            item.addView(box, new FrameLayout.LayoutParams(px(186), px(104), Gravity.START | Gravity.TOP));
            art = new ImageView(act);
            art.setScaleType(ImageView.ScaleType.FIT_XY);
            box.addView(art, new FrameLayout.LayoutParams(-1, -1));
            dim = new View(act);
            dim.setBackgroundColor(0x38000000);
            box.addView(dim, new FrameLayout.LayoutParams(-1, -1));
            ring = new View(act);
            GradientDrawable r = new GradientDrawable();
            r.setCornerRadius(radius);
            r.setStroke(Math.max(2, px(2.6f)), 0xFFFFFFFF);
            ring.setBackground(r);
            ring.setAlpha(0f);
            box.addView(ring, new FrameLayout.LayoutParams(-1, -1));
            item.setOnClickListener(v -> {
                if (entry != null) open(entry);
            });
            item.setOnFocusChangeListener((v, f) -> {
                setFocused(f, true);
                if (f) {
                    recentRow.pin(v);
                    glow.setColour(colour);
                    scrollToRow(recentTitle);
                }
            });
        }

        void setFocused(boolean f, boolean animate) {
            float s = f ? 1.08f : 1f;
            if (!animate) {
                box.setScaleX(s);
                box.setScaleY(s);
                shine.setScaleX(s);
                shine.setScaleY(s);
                shine.setAlpha(f ? 1f : 0f);
                ring.setAlpha(f ? 1f : 0f);
                dim.setAlpha(f ? 0f : 1f);
                return;
            }
            DecelerateInterpolator ease = new DecelerateInterpolator(1.5f);
            box.animate().scaleX(s).scaleY(s).setDuration(200).setInterpolator(ease).start();
            shine.animate().scaleX(s).scaleY(s).alpha(f ? 1f : 0f).setDuration(200).setInterpolator(ease).start();
            ring.animate().alpha(f ? 1f : 0f).setDuration(180).start();
            dim.animate().alpha(f ? 0f : 1f).setDuration(200).start();
        }
    }

    // ---- online rows: wide pictures, title and a line under them ----

    private static String key(Feeds.Card k) {
        return k.kind + ":" + k.id;
    }

    /** Two seconds on a match, or OK on it: its highlights (or build-up) from YouTube in a row just under it. */
    private void showHighlights(FeedRow after, Feeds.Card match) {
        if (highlightFor == match && highlightRow != null && highlightRow.row.getParent() != null) return;
        final Context app = act.getApplicationContext();
        try {
            exec.execute(() -> {
                List<Feeds.Card> got = new ArrayList<>();
                try {
                    got = YouTubeSource.highlights(app, match);
                } catch (Exception ignored) {
                }
                final List<Feeds.Card> videos = got;
                handler.post(() -> {
                    if (videos.isEmpty() || after.row.getParent() != feedsBox) return;
                    if (highlightRow == null) highlightRow = new FeedRow(new FeedGroup(new YouTubeSource()));
                    FeedRow h = highlightRow;
                    highlightFor = match;
                    if (h.title.getParent() != null) ((ViewGroup) h.title.getParent()).removeView(h.title);
                    if (h.row.getParent() != null) ((ViewGroup) h.row.getParent()).removeView(h.row);
                    h.cards = videos;
                    h.title.setText((match.finished ? "Résumés  ·  " : "Avant-match  ·  ") + match.title);
                    h.adapter.notifyDataSetChanged();
                    h.row.pinned = 0;
                    h.row.scrollToPosition(0);
                    h.show(true);
                    int at = feedsBox.indexOfChild(after.row) + 1;
                    feedsBox.addView(h.title, at, h.titleLp);
                    feedsBox.addView(h.row, at + 1, new LinearLayout.LayoutParams(-1, -2));
                    for (Feeds.Card c : videos) loadArt(h.group.source, c);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    /** Puts every row with cards in the order of the moment (RowStats). Never while the remote is in them. */
    private void orderFeeds() {
        if (feedsBox.hasFocus()) return;
        List<FeedRow> all = new ArrayList<>();
        for (FeedGroup g : groups) for (FeedRow r : g.rows) if (!r.cards.isEmpty() && r.key != null) all.add(r);
        List<FeedRow> ordered = RowStats.order(act, all, r -> r.key);
        boolean same = feedsBox.getChildCount() == ordered.size() * 2;
        for (int i = 0; same && i < ordered.size(); i++) same = feedsBox.getChildAt(i * 2) == ordered.get(i).title;
        if (same) return;
        feedsBox.removeAllViews();
        for (FeedRow r : ordered) {
            feedsBox.addView(r.title, r.titleLp);
            feedsBox.addView(r.row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /** One source's rows (YouTube, films & séries or sport). */
    private final class FeedGroup {
        final Feeds.Source source;
        final List<FeedRow> rows = new ArrayList<>();

        FeedGroup(Feeds.Source source) {
            this.source = source;
        }

        void set(List<Feeds.Section> sections) {
            if (sections == null || sections.isEmpty()) return;
            if (feedsBox.hasFocus()) return;   // never reshuffle under the remote: the next refresh will do
            while (rows.size() < sections.size()) rows.add(new FeedRow(this));
            for (int i = 0; i < rows.size(); i++) {
                FeedRow r = rows.get(i);
                if (i < sections.size()) {
                    r.cards = sections.get(i).cards;
                    r.title.setText(sections.get(i).heading);
                    r.key = source.title() + ":" + sections.get(i).name;
                } else {
                    r.cards = new ArrayList<>();
                }
                r.adapter.notifyDataSetChanged();
                r.row.pinned = 0;
                r.row.scrollToPosition(0);
                r.show(!r.cards.isEmpty());
                for (int c = 0; c < Math.min(6, r.cards.size()); c++) loadArt(source, r.cards.get(c));
            }
            orderFeeds();
        }
    }

    private final class FeedRow {
        final FeedGroup group;
        final TextView title;
        final LinearLayout.LayoutParams titleLp;
        final PinnedRow row;
        final CardsAdapter adapter;
        List<Feeds.Card> cards = new ArrayList<>();
        /** "YouTube:Musique": what RowStats counts and orders. */
        String key;

        FeedRow(FeedGroup group) {
            this.group = group;
            title = new TextView(act);
            title.setTextColor(TEXT);
            title.setTypeface(regular);
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * u);
            titleLp = new LinearLayout.LayoutParams(-2, -2);
            titleLp.leftMargin = px(36);
            titleLp.topMargin = px(8);
            row = new PinnedRow(act, px(36));
            adapter = new CardsAdapter(this);
            row.setAdapter(adapter);
            show(false);
        }

        void show(boolean on) {
            int v = on ? View.VISIBLE : View.GONE;
            title.setVisibility(v);
            row.setVisibility(v);
        }
    }

    private final class CardsAdapter extends RecyclerView.Adapter<FeedHolder> {
        private final FeedRow r;

        CardsAdapter(FeedRow r) {
            this.r = r;
        }

        @Override
        public int getItemCount() {
            return r.cards.size();
        }

        @NonNull
        @Override
        public FeedHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new FeedHolder(r);
        }

        @Override
        public void onBindViewHolder(@NonNull FeedHolder h, int position) {
            Feeds.Card k = r.cards.get(position);
            h.card = k;
            h.title.setText(k.title);
            h.channel.setText(k.kind == Feeds.YOUTUBE ? k.sub : k.sub);
            h.badge.setText(k.badge);
            h.badge.setVisibility(k.badge.isEmpty() || k.kind == Feeds.SPORT ? View.GONE : View.VISIBLE);
            GradientDrawable bb = new GradientDrawable();
            bb.setCornerRadius(3f * u);
            bb.setColor(k.badgeColour);
            h.badge.setBackground(bb);
            Bitmap b = arts.get(key(k));
            if (b != null) {
                h.art.setImageBitmap(b);
            } else {
                h.art.setImageDrawable(null);
                loadArt(r.group.source, k);
            }
            h.setFocused(h.itemView.isFocused(), false);
        }
    }

    private void loadArt(Feeds.Source source, Feeds.Card k) {
        final String id = key(k);
        if (!artLoading.add(id)) return;
        final Context app = act.getApplicationContext();
        final int w = px(186), h = px(104);
        try {
            exec.execute(() -> {
                Bitmap got = null;
                try {
                    got = source.card(app, k, w, h);
                } catch (Exception ignored) {
                }
                final Bitmap b = got;
                final int col = b == null ? 0 : YouTubeFeed.colour(b);
                handler.post(() -> {
                    artLoading.remove(id);
                    if (b == null) return;
                    arts.put(id, b);
                    artColours.put(id, col);
                    // The highlights row has its own group, outside groups: without it its thumbnails stayed empty.
                    List<FeedRow> rows = new ArrayList<>();
                    for (FeedGroup g : groups) rows.addAll(g.rows);
                    if (highlightRow != null) rows.add(highlightRow);
                    for (FeedRow r : rows) {
                        for (int i = 0; i < r.row.getChildCount(); i++) {
                            RecyclerView.ViewHolder vh = r.row.getChildViewHolder(r.row.getChildAt(i));
                            if (vh instanceof FeedHolder && ((FeedHolder) vh).card != null
                                    && id.equals(key(((FeedHolder) vh).card))) {
                                ((FeedHolder) vh).art.setImageBitmap(b);
                                if (vh.itemView.isFocused()) glow.setColour(col);
                            }
                        }
                    }
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            artLoading.remove(id);
        }
    }

    private final class FeedHolder extends RecyclerView.ViewHolder {
        Feeds.Card card;
        final FrameLayout box;
        final ImageView art;
        final View dim, ring, shine;
        final TextView title, channel, badge;

        FeedHolder(FeedRow r) {
            super(new FrameLayout(act));
            FrameLayout item = (FrameLayout) itemView;
            item.setLayoutParams(new RecyclerView.LayoutParams(px(204), -2));
            item.setClipChildren(false);
            item.setClipToPadding(false);
            item.setFocusable(true);
            item.setPadding(0, px(10), 0, px(8));
            shine = new View(act);
            shine.setBackground(new android.graphics.drawable.BitmapDrawable(act.getResources(), cardGlow()));
            shine.setAlpha(0f);
            int g = px(GLOW);
            FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(px(186) + 2 * g, px(104) + 2 * g, Gravity.START | Gravity.TOP);
            gl.leftMargin = -g;
            gl.topMargin = -g;
            item.addView(shine, gl);
            box = new FrameLayout(act);
            final float radius = 7f * u;
            box.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            box.setClipToOutline(true);
            box.setElevation(3f * u);
            box.setBackgroundColor(0xFF1E1E22);
            item.addView(box, new FrameLayout.LayoutParams(px(186), px(104), Gravity.START | Gravity.TOP));
            art = new ImageView(act);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            box.addView(art, new FrameLayout.LayoutParams(-1, -1));
            badge = new TextView(act);
            badge.setTextColor(TEXT);
            badge.setTypeface(medium);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, 8.5f * u);
            badge.setPadding(px(4), px(1.2f), px(4), px(1.2f));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
            bl.rightMargin = px(5);
            bl.bottomMargin = px(5);
            box.addView(badge, bl);
            dim = new View(act);
            dim.setBackgroundColor(0x38000000);
            box.addView(dim, new FrameLayout.LayoutParams(-1, -1));
            ring = new View(act);
            GradientDrawable rr = new GradientDrawable();
            rr.setCornerRadius(radius);
            rr.setStroke(Math.max(2, px(2.6f)), 0xFFFFFFFF);
            ring.setBackground(rr);
            ring.setAlpha(0f);
            box.addView(ring, new FrameLayout.LayoutParams(-1, -1));
            title = new TextView(act);
            title.setTextColor(TEXT);
            title.setTypeface(medium);
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 10.5f * u);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(px(186), -2, Gravity.START | Gravity.TOP);
            tl.topMargin = px(104 + 7);
            item.addView(title, tl);
            channel = new TextView(act);
            channel.setTextColor(0x99FFFFFF);
            channel.setTypeface(regular);
            channel.setTextSize(TypedValue.COMPLEX_UNIT_PX, 9.5f * u);
            channel.setSingleLine(true);
            channel.setEllipsize(android.text.TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(px(186), -2, Gravity.START | Gravity.TOP);
            cl.topMargin = px(104 + 7 + 15);
            item.addView(channel, cl);
            item.setOnClickListener(v -> {
                if (card == null) return;
                if (r.key != null) RowStats.open(act, r.key);
                if (card.kind == Feeds.SPORT) {
                    showHighlights(r, card);
                    return;
                }
                r.group.source.open(act, card);
            });
            item.setOnFocusChangeListener((v, f) -> {
                setFocused(f, true);
                if (f) {
                    if (dwell != null) handler.removeCallbacks(dwell);
                    if (card != null && card.kind == Feeds.SPORT) {
                        final Feeds.Card match = card;
                        dwell = () -> {
                            if (item.isFocused()) showHighlights(r, match);
                        };
                        handler.postDelayed(dwell, 2000);
                    }
                    if (r.key != null) RowStats.visit(act, r.key);
                    r.row.pin(v);
                    Integer col = card == null ? null : artColours.get(key(card));
                    if (col != null) glow.setColour(col);
                    scrollToRow(r.title);
                }
            });
        }

        void setFocused(boolean f, boolean animate) {
            float s = f ? 1.08f : 1f;
            float ty = f ? px(5) : 0;
            float ta = f ? 1f : 0.72f;
            if (!animate) {
                box.setScaleX(s);
                box.setScaleY(s);
                shine.setScaleX(s);
                shine.setScaleY(s);
                shine.setAlpha(f ? 1f : 0f);
                ring.setAlpha(f ? 1f : 0f);
                dim.setAlpha(f ? 0f : 1f);
                title.setTranslationY(ty);
                channel.setTranslationY(ty);
                title.setAlpha(ta);
                return;
            }
            DecelerateInterpolator ease = new DecelerateInterpolator(1.5f);
            box.animate().scaleX(s).scaleY(s).setDuration(200).setInterpolator(ease).start();
            shine.animate().scaleX(s).scaleY(s).alpha(f ? 1f : 0f).setDuration(200).setInterpolator(ease).start();
            ring.animate().alpha(f ? 1f : 0f).setDuration(180).start();
            dim.animate().alpha(f ? 0f : 1f).setDuration(200).start();
            title.animate().translationY(ty).alpha(ta).setDuration(200).setInterpolator(ease).start();
            channel.animate().translationY(ty).setDuration(200).setInterpolator(ease).start();
        }
    }

    // ---- views ----

    /** Horizontal row whose focused item stays on the left key line; entering it lands on that item. */
    static final class PinnedRow extends RecyclerView {
        int pinned = NO_POSITION;
        private int keyline;

        PinnedRow(Context c, int keyline) {
            super(c);
            this.keyline = keyline;
            setLayoutManager(new LinearLayoutManager(c, HORIZONTAL, false) {
                @Override
                public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                             @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
                    return false;   // pin() scrolls
                }
            });
            setItemAnimator(null);
            setClipChildren(false);
            setClipToPadding(false);
            setPadding(keyline, 0, keyline, 0);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setHasFixedSize(true);
            setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
            addOnScrollListener(new OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    updateFade();
                }
            });
        }

        /** Items left of the key line fade out, so the row starts at the focused app as on Google TV. */
        void updateFade() {
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                float w = c.getWidth();
                if (w <= 0) continue;
                float a = (c.getLeft() + w / 2f - (keyline - w / 2f)) / w;
                c.setAlpha(Math.max(0f, Math.min(1f, a)));
            }
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            updateFade();
        }

        void setKeyline(int keyline) {
            this.keyline = keyline;
            setPadding(keyline, 0, keyline, 0);
        }

        void pin(View child) {
            int pos = getChildAdapterPosition(child);
            if (pos == NO_POSITION) return;
            pinned = pos;
            int dx = child.getLeft() - keyline;
            if (dx != 0) smoothScrollBy(dx, 0, new DecelerateInterpolator(1.6f), 260);
        }

        @Override
        public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
            if (!hasFocus() && getVisibility() == VISIBLE) {
                ViewHolder vh = pinned == NO_POSITION ? null : findViewHolderForAdapterPosition(pinned);
                if (vh != null) {
                    views.add(vh.itemView);
                    return;
                }
            }
            super.addFocusables(views, direction, focusableMode);
        }

        @Override
        protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
            ViewHolder vh = pinned == NO_POSITION ? null : findViewHolderForAdapterPosition(pinned);
            if (vh != null && vh.itemView.requestFocus()) return true;
            return super.onRequestFocusInDescendants(direction, previouslyFocusedRect);
        }
    }

    /** The top bar: coming up from the rows lands on the selected tab, not the nearest button. */
    static final class Gate extends LinearLayout {
        View preferred;

        Gate(Context c) {
            super(c);
        }

        @Override
        public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
            if (!hasFocus() && preferred != null && direction == FOCUS_UP) {
                views.add(preferred);
                return;
            }
            super.addFocusables(views, direction, focusableMode);
        }
    }

    /** Vertical scroll that leaves the scrolling to the rows' focus handlers (no jump to the focused child). */
    static final class Scroller extends ScrollView {
        Scroller(Context c) {
            super(c);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }
    }

    /** Near-black background with a soft glow of the focused app's colour from the top left. */
    static final class Glow extends View {
        private final Paint paint = new Paint();
        private int colour = 0xFF3A3A3A;
        private ValueAnimator anim;

        Glow(Context c) {
            super(c);
        }

        void setColour(int target) {
            target |= 0xFF000000;
            if (target == colour && anim == null) return;
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofObject(new ArgbEvaluator(), colour, target);
            anim.setDuration(450);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(a -> {
                colour = (int) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            int rgb = colour & 0x00FFFFFF;
            paint.setShader(new RadialGradient(w * 0.1f, h * 0.22f, w * 0.78f,
                    new int[]{0x4C000000 | rgb, 0x1C000000 | rgb, rgb}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(new LinearGradient(0, h * 0.45f, 0, h, 0x00000000, 0x66000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
        }
    }
}
