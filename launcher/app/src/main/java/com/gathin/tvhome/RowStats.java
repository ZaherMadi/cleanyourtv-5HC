package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Which online rows get used, to order them: the rows come in a random order, weighted by use over the last weeks,
 * so a row opened often rises to the top more and more often while the others still come round. One draw per row
 * holds for a few hours, so rows do not jump about while Home is being used.
 * Uses are counted per day: a card opened counts 3, a row visited (focused) counts 1 (once per 10 minutes).
 */
final class RowStats {
    private static final String PREFS = "row_stats";
    private static final long DAY = 24L * 3600 * 1000;
    private static final long DRAW_MS = 3L * 3600 * 1000;
    private static final Map<String, Long> lastVisit = new HashMap<>();
    private static final Map<String, Double> draws = new HashMap<>();
    private static long drawnAt;
    private static final Random random = new Random();

    private RowStats() {
    }

    static void visit(Context c, String key) {
        long now = System.currentTimeMillis();
        Long last = lastVisit.get(key);
        if (last != null && now - last < 10 * 60 * 1000) return;
        lastVisit.put(key, now);
        add(c, key, 0, 1);
    }

    static void open(Context c, String key) {
        add(c, key, 1, 0);
    }

    private static synchronized void add(Context c, String key, int opens, int visits) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long today = System.currentTimeMillis() / DAY;
        StringBuilder out = new StringBuilder();
        boolean found = false;
        for (String e : p.getString(key, "").split(";")) {
            String[] f = e.split(":");
            if (f.length != 3) continue;
            long day = Long.parseLong(f[0]);
            if (today - day > 28) continue;   // four weeks are enough to see a habit
            int o = Integer.parseInt(f[1]), v = Integer.parseInt(f[2]);
            if (day == today) {
                o += opens;
                v += visits;
                found = true;
            }
            out.append(day).append(':').append(o).append(':').append(v).append(';');
        }
        if (!found) out.append(today).append(':').append(opens).append(':').append(visits).append(';');
        p.edit().putString(key, out.toString()).apply();
    }

    /** This week's uses count fully, the three weeks before less and less. */
    static double score(Context c, String key) {
        long today = System.currentTimeMillis() / DAY;
        double s = 0;
        for (String e : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, "").split(";")) {
            String[] f = e.split(":");
            if (f.length != 3) continue;
            long age = today - Long.parseLong(f[0]);
            double weight = age < 7 ? 1.0 : age < 14 ? 0.5 : age < 21 ? 0.25 : 0.12;
            s += weight * (3 * Integer.parseInt(f[1]) + Integer.parseInt(f[2]));
        }
        return s;
    }

    /** The row's place in the random order of the moment (higher comes first). */
    static synchronized double draw(Context c, String key) {
        long now = System.currentTimeMillis();
        if (now - drawnAt > DRAW_MS) {
            draws.clear();
            drawnAt = now;
        }
        Double d = draws.get(key);
        if (d == null) {
            // Weighted random order (Efraimidis–Spirakis): u^(1/w), w grows with use.
            double w = 1 + score(c, key) / 4.0;
            d = Math.pow(random.nextDouble(), 1.0 / w);
            draws.put(key, d);
        }
        return d;
    }

    interface KeyOf<T> {
        String key(T item);
    }

    static <T> List<T> order(Context c, List<T> items, KeyOf<T> keyOf) {
        List<T> out = new ArrayList<>(items);
        final Map<T, Double> d = new HashMap<>();
        for (T t : out) d.put(t, draw(c, keyOf.key(t)));
        Collections.sort(out, (a, b) -> Double.compare(d.get(b), d.get(a)));
        return out;
    }
}
