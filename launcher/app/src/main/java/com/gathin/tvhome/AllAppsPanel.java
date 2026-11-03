package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The bottom "all apps" grid: the app row widens upwards into a panel tinted with the colour of the background
 * behind it, and the arrows move over every installed app. Back, or Up from the first row, closes it.
 */
final class AllAppsPanel {
    private static final int COLUMNS = 6;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    private static final class AppItem {
        final String pkg;
        final String label;
        final ComponentName component;
        final boolean tv;
        Drawable icon;

        AppItem(String pkg, String label, ComponentName component, boolean tv) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.tv = tv;
        }
    }

    private final Context context;
    private final FrameLayout root;
    private final Set<String> hidden;
    private final boolean animations;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<AppItem> items = new ArrayList<>();
    private FrameLayout panel;
    private RecyclerView list;
    private int focused;
    private boolean showing;
    private boolean loading;

    AllAppsPanel(Context context, FrameLayout root, Set<String> hidden, boolean animations) {
        this.context = context;
        this.root = root;
        this.hidden = hidden;
        this.animations = animations;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    boolean isShowing() {
        return showing;
    }

    void show() {
        if (showing || loading) return;
        loading = true;
        final int tint = sampleBackground();
        LOADER.execute(() -> {
            final List<AppItem> found = load();
            handler.post(() -> {
                loading = false;
                if (showing) return;
                items.clear();
                items.addAll(found);
                build(tint);
            });
        });
    }

    void hide() {
        if (!showing || panel == null) return;
        showing = false;
        final FrameLayout view = panel;
        panel = null;
        list = null;
        if (animations) {
            view.animate().translationY(view.getHeight()).setDuration(200)
                    .withEndAction(() -> root.removeView(view)).start();
        } else {
            root.removeView(view);
        }
    }

    /** Returns true when the key was consumed. Only called while the panel is showing. */
    boolean onKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) hide();
            return true;
        }
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT
                && code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        if (!down) return true;
        int n = items.size();
        if (n == 0 || list == null) return true;
        int pos = focused;
        int target = pos;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (pos % COLUMNS > 0) target = pos - 1;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (pos % COLUMNS < COLUMNS - 1 && pos + 1 < n) target = pos + 1;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (pos < COLUMNS) {
                    hide();
                    return true;
                }
                target = pos - COLUMNS;
                break;
            default:   // DOWN
                if (pos + COLUMNS < n) target = pos + COLUMNS;
                else if ((n - 1) / COLUMNS > pos / COLUMNS) target = n - 1;
        }
        if (target != pos) focus(target);
        return true;
    }

    // ------------------------------------------------------------------------------------------

    private void focus(final int position) {
        final RecyclerView rv = list;
        if (rv == null) return;
        focused = position;
        rv.scrollToPosition(position);
        rv.post(() -> {
            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(position);
            if (vh != null) vh.itemView.requestFocus();
        });
    }

    /** Average colour of what is on screen now, so the panel follows the background (video backgrounds read black). */
    private int sampleBackground() {
        try {
            Bitmap bmp = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.scale(24f / Math.max(1, root.getWidth()), 14f / Math.max(1, root.getHeight()));
            root.draw(canvas);
            long r = 0, g = 0, b = 0;
            int count = 0;
            for (int y = 6; y < 14; y++) {
                for (int x = 0; x < 24; x++) {
                    int c = bmp.getPixel(x, y);
                    r += Color.red(c);
                    g += Color.green(c);
                    b += Color.blue(c);
                    count++;
                }
            }
            bmp.recycle();
            return Color.rgb((int) (r / count), (int) (g / count), (int) (b / count));
        } catch (RuntimeException e) {
            return Color.BLACK;
        }
    }

    private List<AppItem> load() {
        PackageManager pm = context.getPackageManager();
        String self = context.getPackageName();
        Map<String, AppItem> unique = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            boolean tv = Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category);
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(self) || hidden.contains(pkg) || unique.containsKey(pkg)) continue;
                CharSequence label = ri.loadLabel(pm);
                AppItem item = new AppItem(pkg, label == null ? pkg : label.toString(),
                        new ComponentName(pkg, ri.activityInfo.name), tv);
                try {
                    item.icon = ri.loadIcon(pm);
                } catch (RuntimeException ignored) {
                }
                unique.put(pkg, item);
            }
        }
        List<AppItem> out = new ArrayList<>(unique.values());
        final Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label, b.label));
        return out;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private void build(int tint) {
        int height = Math.round(root.getHeight() * 0.64f);
        FrameLayout view = new FrameLayout(context);
        int dark = Color.argb(0xEC, (int) (Color.red(tint) * 0.35f), (int) (Color.green(tint) * 0.35f),
                (int) (Color.blue(tint) * 0.35f));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark);
        bg.setCornerRadii(new float[]{dp(28), dp(28), dp(28), dp(28), 0, 0, 0, 0});
        view.setBackground(bg);
        view.setClickable(true);

        TextView title = new TextView(context);
        title.setText(R.string.app_library);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setPadding(dp(48), dp(22), dp(48), dp(6));
        view.addView(title, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        RecyclerView rv = new RecyclerView(context);
        rv.setLayoutManager(new GridLayoutManager(context, COLUMNS));
        rv.setClipToPadding(false);
        rv.setPadding(dp(40), dp(8), dp(40), dp(24));
        rv.setItemAnimator(null);
        rv.setAdapter(new Adapter());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.topMargin = dp(58);
        view.addView(rv, lp);

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height,
                Gravity.BOTTOM);
        root.addView(view, plp);
        panel = view;
        list = rv;
        showing = true;
        focused = 0;
        if (animations) {
            view.setTranslationY(height);
            view.animate().translationY(0).setDuration(240).start();
        }
        rv.post(() -> focus(0));
    }

    private void launch(AppItem item) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(item.tv ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(item.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(intent);
            hide();
        } catch (RuntimeException ignored) {
        }
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(8), dp(12), dp(8), dp(10));
            cell.setFocusable(true);
            cell.setClickable(true);
            StateListDrawable states = new StateListDrawable();
            GradientDrawable on = new GradientDrawable();
            on.setColor(Color.argb(0x45, 255, 255, 255));
            on.setStroke(dp(2), Color.argb(0xB0, 255, 255, 255));
            on.setCornerRadius(dp(16));
            states.addState(new int[]{android.R.attr.state_focused}, on);
            states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
            cell.setBackground(states);
            ImageView icon = new ImageView(context);
            icon.setId(android.R.id.icon);
            cell.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
            TextView label = new TextView(context);
            label.setId(android.R.id.text1);
            label.setTextColor(Color.WHITE);
            label.setTextSize(13f);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            cell.addView(label, lp);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(cell) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final AppItem item = items.get(position);
            ((ImageView) holder.itemView.findViewById(android.R.id.icon)).setImageDrawable(item.icon);
            ((TextView) holder.itemView.findViewById(android.R.id.text1)).setText(item.label);
            holder.itemView.setOnClickListener(v -> launch(item));
            holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    int p = holder.getBindingAdapterPosition();
                    if (p >= 0) focused = p;
                }
                v.animate().scaleX(hasFocus ? 1.06f : 1f).scaleY(hasFocus ? 1.06f : 1f).setDuration(120).start();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
