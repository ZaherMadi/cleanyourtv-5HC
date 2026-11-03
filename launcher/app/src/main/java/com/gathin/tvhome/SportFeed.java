package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sport as a volet: the big football matches of today, tomorrow and the day after, from API-Football
 * (api-sports.io, key set with {@code --es football_key}). One request per day listed (all matches of that day,
 * filtered here to the competitions people follow in France), refreshed every 30 minutes for today and every 6 hours
 * otherwise: well inside the free plan's 100 requests a day. Team logos come from the API's image server, which does
 * not count towards the quota, and are kept; a team without one gets a badge drawn with its initials.
 */
final class SportFeed implements Feeds.Source {
    static final String BASE = "https://v3.football.api-sports.io";
    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    /** name, heading, day offset. */
    private static final Object[][] ROWS = {
            {"Aujourd’hui", "Football · les matchs du jour", 0},
            {"Demain", "Football · demain", 1},
            // The free plan only serves yesterday to tomorrow: yesterday's results instead of the day after.
            {"Résultats d’hier", "Football · les résultats d’hier", -1},
    };

    /** League id → French name, colour, priority (lower first). */
    private static final Map<Integer, Object[]> LEAGUES = new HashMap<>();

    static {
        LEAGUES.put(2, new Object[]{"Ligue des champions", 0xFF0B1F5C, 0});
        LEAGUES.put(61, new Object[]{"Ligue 1", 0xFF14213D, 1});
        LEAGUES.put(3, new Object[]{"Ligue Europa", 0xFF7A3E00, 2});
        LEAGUES.put(848, new Object[]{"Ligue Conférence", 0xFF0F5C3A, 3});
        LEAGUES.put(1, new Object[]{"Coupe du monde", 0xFF5C0F2A, 0});
        LEAGUES.put(4, new Object[]{"Euro", 0xFF0B2F5C, 0});
        LEAGUES.put(5, new Object[]{"Ligue des nations", 0xFF0B1F5C, 2});
        LEAGUES.put(32, new Object[]{"Qualif. Coupe du monde", 0xFF3A0F5C, 2});
        LEAGUES.put(66, new Object[]{"Coupe de France", 0xFF0D2B6B, 3});
        LEAGUES.put(39, new Object[]{"Premier League", 0xFF3D0042, 4});
        LEAGUES.put(140, new Object[]{"LaLiga", 0xFF5C0F14, 5});
        LEAGUES.put(135, new Object[]{"Serie A", 0xFF07335C, 6});
        LEAGUES.put(78, new Object[]{"Bundesliga", 0xFF5C0A0A, 7});
        LEAGUES.put(62, new Object[]{"Ligue 2", 0xFF1F2A44, 8});
    }

    @Override
    public String title() {
        return "Sport";
    }

    @Override
    public List<Feeds.Section> load(Context c) {
        List<Feeds.Section> out = new ArrayList<>();
        String key = Keys.football(c);
        if (key.isEmpty()) return out;
        Map<String, String> headers = new HashMap<>();
        headers.put("x-apisports-key", key);
        for (Object[] row : ROWS) {
            int offset = (Integer) row[2];
            String date = LocalDate.now(ZONE).plusDays(offset).toString();
            String url = BASE + "/fixtures?date=" + date + "&timezone=Europe/Paris";
            long fresh = offset == 0 ? 30L * 60 * 1000 : offset < 0 ? 24L * 3600 * 1000 : 6L * 3600 * 1000;
            String json = Net.json(c, "football_" + date + ".json", fresh, url, headers);
            List<Feeds.Card> cards = parse(json);
            if (!cards.isEmpty()) out.add(new Feeds.Section((String) row[0], (String) row[1], cards));
        }
        return out;
    }

    private static List<Feeds.Card> parse(String json) {
        List<Feeds.Card> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONObject root = new JSONObject(json);
            Object errors = root.opt("errors");
            if (errors instanceof JSONObject && ((JSONObject) errors).length() > 0) Log.w("Home/Football", "API: " + errors);
            JSONArray list = root.optJSONArray("response");
            if (list == null) return out;
            final Map<Feeds.Card, long[]> order = new HashMap<>();
            DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH:mm");
            for (int i = 0; i < list.length(); i++) {
                JSONObject f = list.getJSONObject(i);
                JSONObject league = f.optJSONObject("league");
                if (league == null) continue;
                Object[] known = LEAGUES.get(league.optInt("id"));
                if (known == null) continue;
                JSONObject fixture = f.getJSONObject("fixture");
                JSONObject status = fixture.optJSONObject("status");
                String st = status == null ? "NS" : status.optString("short", "NS");
                if (st.equals("PST") || st.equals("CANC") || st.equals("ABD") || st.equals("AWD") || st.equals("WO")) continue;
                JSONObject teams = f.getJSONObject("teams");
                JSONObject home = teams.getJSONObject("home"), away = teams.getJSONObject("away");
                Feeds.Card k = new Feeds.Card(Feeds.SPORT, String.valueOf(fixture.optLong("id")));
                k.homeId = home.optString("logo", "");
                k.awayId = away.optString("logo", "");
                String hn = home.optString("name"), an = away.optString("name");
                k.homeCode = code(hn);
                k.awayCode = code(an);
                k.title = hn + "  –  " + an;
                k.homeName = hn;
                k.awayName = an;
                k.tournament = (String) known[0];
                k.tint = (Integer) known[1];
                long ts = fixture.optLong("timestamp") * 1000L;
                k.startMs = ts;
                String time = hm.format(Instant.ofEpochMilli(ts).atZone(ZONE));
                JSONObject goals = f.optJSONObject("goals");
                String score = goal(goals, "home") + " – " + goal(goals, "away");
                int elapsed = status == null ? 0 : status.optInt("elapsed", 0);
                switch (st) {
                    case "1H": case "2H": case "ET": case "BT": case "P": case "LIVE": case "INT": case "SUSP":
                        k.live = true;
                        k.centre = score;
                        k.badge = "EN DIRECT";
                        k.badgeColour = 0xE6D50000;
                        k.sub = k.tournament + "  ·  " + (elapsed > 0 ? elapsed + "’" : "en direct") + "  ·  " + score;
                        break;
                    case "HT":
                        k.live = true;
                        k.centre = score;
                        k.badge = "MI-TEMPS";
                        k.badgeColour = 0xE6D50000;
                        k.sub = k.tournament + "  ·  mi-temps  ·  " + score;
                        break;
                    case "FT": case "AET": case "PEN":
                        k.finished = true;
                        k.centre = score;
                        k.badge = "TERMINÉ";
                        k.sub = k.tournament + "  ·  terminé  ·  " + score;
                        break;
                    default:
                        k.centre = time;
                        k.badge = time;
                        k.sub = k.tournament + "  ·  " + time;
                }
                // Live first, then the bigger competitions, then kick-off time.
                order.put(k, new long[]{k.live ? 0 : 1, ((Integer) known[2]), ts});
                out.add(k);
            }
            Collections.sort(out, (a, b) -> {
                long[] x = order.get(a), y = order.get(b);
                for (int i = 0; i < 3; i++) if (x[i] != y[i]) return Long.compare(x[i], y[i]);
                return 0;
            });
        } catch (Exception ex) {
            Log.w("Home/Football", "bad list", ex);
        }
        return out.size() > 20 ? new ArrayList<>(out.subList(0, 20)) : out;
    }

    private static String goal(JSONObject goals, String side) {
        if (goals == null || goals.isNull(side)) return "0";
        return String.valueOf(goals.optInt(side));
    }

    /** The usual short names, where the rule below would be ambiguous or unlike what fans say. */
    private static final Map<String, String> CODES = new HashMap<>();

    static {
        String[][] known = {
                {"Paris Saint Germain", "PSG"}, {"Olympique Marseille", "OM"}, {"Marseille", "OM"}, {"Lyon", "OL"},
                {"Olympique Lyonnais", "OL"}, {"Real Madrid", "RMA"}, {"Real Betis", "BET"}, {"Real Sociedad", "RSO"},
                {"Atletico Madrid", "ATM"}, {"Borussia Dortmund", "BVB"}, {"Borussia Monchengladbach", "BMG"},
                {"Bayer Leverkusen", "B04"}, {"Manchester United", "MUN"}, {"Manchester City", "MCI"},
                {"Inter", "INT"}, {"AC Milan", "MIL"}, {"Juventus", "JUV"}, {"Napoli", "NAP"}, {"Bayern München", "FCB"},
                {"Barcelona", "BAR"}, {"Stade Rennais", "REN"}, {"Stade Brestois 29", "BRE"}, {"RB Leipzig", "RBL"},
                {"Sporting CP", "SCP"}, {"Benfica", "SLB"}, {"FC Porto", "POR"}, {"Tottenham", "TOT"},
        };
        for (String[] k : known) CODES.put(k[0], k[1]);
    }

    /** "Paris Saint Germain" → PSG, "Olympique Lyonnais" → OL, "Galatasaray" → GAL. */
    static String code(String name) {
        String known = CODES.get(name);
        if (known != null) return known;
        List<String> words = new ArrayList<>();
        for (String w : name.replace('-', ' ').split("\\s+")) {
            String u = w.toUpperCase(Locale.ROOT);
            if (u.isEmpty() || u.equals("FC") || u.equals("AFC") || u.equals("CF") || u.equals("AS") || u.equals("SC")
                    || u.equals("AC") || u.equals("SS") || u.equals("SSC") || u.equals("US") || u.equals("OGC")
                    || u.equals("RC") || u.equals("CD") || u.equals("SL") || u.equals("1.") || u.matches("\\d+")) continue;
            words.add(u);
        }
        if (words.isEmpty()) return name.length() > 3 ? name.substring(0, 3).toUpperCase(Locale.ROOT) : name.toUpperCase(Locale.ROOT);
        if (words.size() >= 3) return "" + words.get(0).charAt(0) + words.get(1).charAt(0) + words.get(2).charAt(0);
        String w = words.get(0);
        return w.length() > 3 ? w.substring(0, 3) : w;
    }

    /** The team's logo at size x size (kept on disk), or a round badge with its initials when it has none. */
    private Bitmap logo(Context c, String url, String code, int tint, int size) {
        Bitmap src = url == null || url.isEmpty() ? null : Net.image(c, url, null, 256, 256, true);
        if (src != null) return src.getWidth() == size ? src : Bitmap.createScaledBitmap(src, size, size, true);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, size, size, lighten(tint, 0.45f), lighten(tint, 0.15f), Shader.TileMode.CLAMP));
        cv.drawCircle(size / 2f, size / 2f, size * 0.46f, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(size * 0.04f);
        p.setColor(0xCCFFFFFF);
        cv.drawCircle(size / 2f, size / 2f, size * 0.46f, p);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(0xFFFFFFFF);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setTextSize(size * 0.3f);
        cv.drawText(code, size / 2f, size / 2f + size * 0.105f, t);
        return out;
    }

    @Override
    public Bitmap card(Context c, Feeds.Card k, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new LinearGradient(0, 0, w, h, lighten(k.tint, 0.18f), darken(k.tint, 0.55f), Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w / 2f, h * 0.45f, w * 0.45f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
        p.setShader(null);
        int ls = Math.round(h * 0.42f);
        float cy = h * 0.42f;
        Bitmap hl = logo(c, k.homeId, k.homeCode, k.tint, ls), al = logo(c, k.awayId, k.awayCode, k.tint, ls);
        float lx = w * 0.22f, rx = w * 0.78f;
        cv.drawBitmap(hl, lx - ls / 2f, cy - ls / 2f, p);
        cv.drawBitmap(al, rx - ls / 2f, cy - ls / 2f, p);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextAlign(Paint.Align.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextSize(h * 0.1f);
        t.setColor(0xD9FFFFFF);
        cv.drawText(k.homeCode, lx, cy + ls / 2f + h * 0.13f, t);
        cv.drawText(k.awayCode, rx, cy + ls / 2f + h * 0.13f, t);
        t.setColor(0xFFFFFFFF);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setTextSize(h * (k.centre.length() > 5 ? 0.17f : 0.2f));
        cv.drawText(k.centre, w / 2f, cy + h * 0.07f, t);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        t.setTextSize(h * 0.085f);
        t.setColor(0xB3FFFFFF);
        String tour = k.tournament.length() > 28 ? k.tournament.substring(0, 27) + "…" : k.tournament;
        cv.drawText(tour, w / 2f, h * 0.93f, t);
        if (k.live) {
            float pw = h * 0.42f, ph = h * 0.13f;
            RectF r = new RectF(w / 2f - pw / 2, h * 0.08f, w / 2f + pw / 2, h * 0.08f + ph);
            Paint red = new Paint(Paint.ANTI_ALIAS_FLAG);
            red.setColor(0xFFD50000);
            cv.drawRoundRect(r, ph / 2, ph / 2, red);
            t.setColor(0xFFFFFFFF);
            t.setTextSize(ph * 0.62f);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            cv.drawText(k.badge, w / 2f, r.centerY() + ph * 0.22f, t);
        }
        return out;
    }

    @Override
    public Bitmap backdrop(Context c, Feeds.Card k) {
        int w = 1280, h = 720;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        cv.drawColor(darken(k.tint, 0.7f));
        p.setShader(new RadialGradient(w * 0.62f, h * 0.38f, w * 0.7f, lighten(k.tint, 0.1f), darken(k.tint, 0.75f), Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
        p.setShader(null);
        int ls = Math.round(h * 0.5f);
        Bitmap hl = logo(c, k.homeId, k.homeCode, k.tint, ls), al = logo(c, k.awayId, k.awayCode, k.tint, ls);
        p.setAlpha(150);
        cv.drawBitmap(hl, w * 0.5f - ls * 1.05f, h * 0.12f, p);
        cv.drawBitmap(al, w * 0.5f + ls * 0.25f, h * 0.12f, p);
        return out;
    }

    @Override
    public void open(Context c, Feeds.Card k) {
        // No app is known to show a given match: nothing to open yet.
    }

    private static int lighten(int c, float t) {
        return Color.rgb(Math.round(Color.red(c) + (255 - Color.red(c)) * t),
                Math.round(Color.green(c) + (255 - Color.green(c)) * t), Math.round(Color.blue(c) + (255 - Color.blue(c)) * t));
    }

    private static int darken(int c, float t) {
        return Color.rgb(Math.round(Color.red(c) * (1 - t)), Math.round(Color.green(c) * (1 - t)), Math.round(Color.blue(c) * (1 - t)));
    }
}
