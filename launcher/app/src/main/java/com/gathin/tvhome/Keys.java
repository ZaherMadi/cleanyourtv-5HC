package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;

import java.io.File;

/**
 * API keys for the online rows, kept in the launcher's settings on the TV and never in the source:
 * {@code am start -n com.gathin.tvhome/.MainActivity --es rapidapi_key KEY} (films and series, sport, weather).
 * The YouTube key has its own extra, see {@link YouTubeFeed}.
 */
final class Keys {
    static final String RAPID = "rapidapi_key";
    static final String FOOTBALL = "football_key";

    private Keys() {
    }

    static String rapid(Context c) {
        String k = c.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString(RAPID, "");
        return k == null ? "" : k.trim();
    }

    /** API-Football (api-sports.io) key, for the sport volet. */
    static String football(Context c) {
        String k = c.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString(FOOTBALL, "");
        return k == null ? "" : k.trim();
    }

    /** Saves the keys passed on Home's intent ({@code --es rapidapi_key} / {@code --es football_key}). True when one was there. */
    static boolean applyExtra(Context c, Intent i) {
        if (i == null || (!i.hasExtra(RAPID) && !i.hasExtra(FOOTBALL))) return false;
        android.content.SharedPreferences.Editor e = c.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit();
        final java.util.Set<String> prefixes = new java.util.HashSet<>();
        for (String k : new String[]{RAPID, FOOTBALL}) {
            if (!i.hasExtra(k)) continue;
            String v = i.getStringExtra(k);
            i.removeExtra(k);
            e.putString(k, v == null ? "" : v.trim());
            if (k.equals(RAPID)) {
                prefixes.add("shows_");
                prefixes.add("weather");
            } else {
                prefixes.add("football_");
            }
        }
        e.commit();
        // Only the lists made with the changed key: the others cost requests to fetch again.
        File[] old = new File(c.getCacheDir(), "feeds").listFiles();
        if (old != null) for (File f : old) for (String p : prefixes) if (f.getName().startsWith(p)) f.delete();
        return true;
    }
}
