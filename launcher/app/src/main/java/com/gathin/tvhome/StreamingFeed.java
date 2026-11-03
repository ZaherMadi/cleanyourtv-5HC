package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Films and series popular in France on the streaming apps installed on the TV, from the Streaming Availability API
 * (RapidAPI). Cards use the landscape poster with the title art; OK opens the title in its app (deep link), or the app.
 * Lists are cached 12 hours: a handful of requests a day.
 */
final class StreamingFeed implements Feeds.Source {
    static final String HOST = "streaming-availability.p.rapidapi.com";
    private static final long FRESH_MS = 12L * 3600 * 1000;

    /** Service id → Android TV package, short name and colour. */
    private static final Map<String, String[]> SERVICES = new HashMap<>();

    static {
        SERVICES.put("netflix", new String[]{"com.netflix.mediaclient", "Netflix", "#E50914"});
        SERVICES.put("prime", new String[]{"com.amazon.amazonvideo.livingroom", "Prime Video", "#00A8E1"});
        SERVICES.put("disney", new String[]{"com.disney.disneyplus", "Disney+", "#113CCF"});
        SERVICES.put("crunchyroll", new String[]{"com.crunchyroll.crunchyroid", "Crunchyroll", "#F47521"});
        SERVICES.put("apple", new String[]{"com.apple.atve.androidtv.appletv", "Apple TV", "#555555"});
        SERVICES.put("hbo", new String[]{"com.wbd.stream", "HBO Max", "#5822B4"});
        SERVICES.put("paramount", new String[]{"com.cbs.ca", "Paramount+", "#0064FF"});
    }

    /** name, heading, catalogs, show type ("" for both), minimum rating. */
    private static final String[][] ROWS = {
            {"Netflix", "Tendances sur Netflix", "netflix", "", "60"},
            {"Prime Video", "Tendances sur Prime Video", "prime.subscription", "", "60"},
            {"Disney+", "Tendances sur Disney+", "disney", "", "60"},
            {"Films du moment", "Films du moment", "netflix,prime.subscription,disney", "movie", "65"},
    };

    @Override
    public String title() {
        return "Films & séries";
    }

    @Override
    public List<Feeds.Section> load(Context c) {
        List<Feeds.Section> out = new ArrayList<>();
        java.util.Map<String, String> headers = Net.rapid(c, HOST);
        if (headers == null) return out;
        Set<String> seen = new HashSet<>();
        for (int r = 0; r < ROWS.length; r++) {
            String[] row = ROWS[r];
            // Only the services whose app is on the TV.
            StringBuilder cats = new StringBuilder();
            for (String cat : row[2].split(",")) {
                String[] svc = SERVICES.get(cat.split("\\.")[0]);
                if (svc != null && Feeds.installed(c, svc[0])) {
                    if (cats.length() > 0) cats.append(',');
                    cats.append(cat);
                }
            }
            if (cats.length() == 0) continue;
            String url = "https://" + HOST + "/shows/search/filters?country=fr&output_language=fr&series_granularity=show"
                    + "&order_by=popularity_1month&order_direction=desc&rating_min=" + row[4]
                    + "&catalogs=" + Uri.encode(cats.toString())
                    + (row[3].isEmpty() ? "" : "&show_type=" + row[3]);
            String json = Net.json(c, "shows_" + r + ".json", FRESH_MS, url, headers);
            List<Feeds.Card> cards = parse(c, json, cats.toString(), seen);
            if (!cards.isEmpty()) out.add(new Feeds.Section(row[0], row[1], cards));
        }
        return out;
    }

    private static List<Feeds.Card> parse(Context c, String json, String catalogs, Set<String> seen) {
        List<Feeds.Card> out = new ArrayList<>();
        if (json == null) return out;
        Set<String> wanted = new HashSet<>();
        for (String cat : catalogs.split(",")) wanted.add(cat.split("\\.")[0]);
        try {
            JSONArray shows = new JSONObject(json).optJSONArray("shows");
            if (shows == null) return out;
            for (int i = 0; i < shows.length(); i++) {
                JSONObject s = shows.getJSONObject(i);
                String id = s.optString("id");
                JSONObject images = s.optJSONObject("imageSet");
                JSONObject poster = images == null ? null : images.optJSONObject("horizontalPoster");
                JSONObject backdrop = images == null ? null : images.optJSONObject("horizontalBackdrop");
                if (poster == null) continue;
                JSONObject opts = s.optJSONObject("streamingOptions");
                JSONArray fr = opts == null ? null : opts.optJSONArray("fr");
                if (fr == null) continue;
                JSONObject pick = null;
                for (int o = 0; o < fr.length() && pick == null; o++) {
                    JSONObject opt = fr.getJSONObject(o);
                    String svc = opt.getJSONObject("service").optString("id");
                    String type = opt.optString("type");
                    if (wanted.contains(svc) && ("subscription".equals(type) || "free".equals(type))) pick = opt;
                }
                if (pick == null) continue;
                String svcId = pick.getJSONObject("service").optString("id");
                String[] svc = SERVICES.get(svcId);
                if (svc == null) continue;
                Feeds.Card k = new Feeds.Card(Feeds.SHOWS, id);
                k.title = s.optString("title");
                boolean series = "series".equals(s.optString("showType"));
                int year = s.optInt(series ? "firstAirYear" : "releaseYear", 0);
                StringBuilder sub = new StringBuilder(svc[1]).append("  ·  ").append(series ? "Série" : "Film");
                if (year > 0) sub.append("  ·  ").append(year);
                JSONArray genres = s.optJSONArray("genres");
                if (genres != null && genres.length() > 0) sub.append("  ·  ").append(genres.getJSONObject(0).optString("name"));
                k.sub = sub.toString();
                // No service tag on the cards: the app's own logo stands beside the title in the hero instead.
                k.image = poster.optString("w720", poster.optString("w480"));
                k.backdrop = backdrop == null ? null : backdrop.optString("w1080", backdrop.optString("w720"));
                k.link = pick.optString("link");
                k.pkg = svc[0];
                k.iconPkg = svc[0];
                // The same title in two rows only once on screen would look like a bug: keep the first.
                if (seen.add(id)) out.add(k);
            }
        } catch (Exception e) {
            Log.w("Home/Shows", "bad list", e);
        }
        return out;
    }

    @Override
    public Bitmap card(Context c, Feeds.Card k, int w, int h) {
        return Net.image(c, k.image, null, w, h);
    }

    @Override
    public Bitmap backdrop(Context c, Feeds.Card k) {
        Bitmap b = Net.image(c, k.backdrop, null, 1280, 720);
        return b != null ? b : Net.image(c, k.image, null, 1280, 720);
    }

    @Override
    public void open(Context c, Feeds.Card k) {
        Feeds.openIn(c, k.link, k.pkg);
    }
}
