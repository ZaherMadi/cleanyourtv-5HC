package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube rows for Home V2 (5HC): the most popular videos in France, overall and per category, from the YouTube
 * Data API. The API key is never in the source: it is set on the TV with
 * {@code am start -n com.gathin.tvhome/.MainActivity --es youtube_key KEY} (or config.xml youtube_api_key).
 * Lists are cached for a few hours, thumbnails on disk, so Home shows them at once and the quota stays tiny.
 */
final class YouTubeFeed {
    static final String KEY_PREF = "youtube_key";
    private static final String TAG = "HomeV2/YouTube";
    private static final long FRESH_MS = 3L * 3600 * 1000;
    static final String YOUTUBE_TV = "com.google.android.youtube.tv";

    /** Row titles and their YouTube category ("" for all videos). */
    static final String[][] ROWS = {
            {"Tendances sur YouTube", ""},
            {"Musique", "10"},
            {"Jeux vidéo", "20"},
            {"Sport", "17"},
    };

    static final class Video {
        final String id, title, channel, duration;

        Video(String id, String title, String channel, String duration) {
            this.id = id;
            this.title = title;
            this.channel = channel;
            this.duration = duration;
        }
    }

    private YouTubeFeed() {
    }

    static String key(Context c) {
        String k = c.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString(KEY_PREF, null);
        if (k == null || k.isEmpty()) {
            int id = c.getResources().getIdentifier("youtube_api_key", "string", c.getPackageName());
            k = id == 0 ? "" : c.getString(id);
        }
        return k.trim();
    }

    /** {@code --es youtube_key KEY} on Home's intent: saves it (an empty value removes it). */
    static void applyExtra(Context c, Intent i) {
        if (i == null || !i.hasExtra(KEY_PREF)) return;
        String v = i.getStringExtra(KEY_PREF);
        i.removeExtra(KEY_PREF);
        SharedPreferences.Editor e = c.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit();
        if (v == null || v.trim().isEmpty()) e.remove(KEY_PREF);
        else e.putString(KEY_PREF, v.trim());
        e.commit();
        // A new key: drop the cached lists so the rows load with it.
        File[] old = new File(c.getCacheDir(), "yt").listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    /**
     * The row's videos: the cached list while fresh, else the network (falling back to a stale cache). Null when
     * there is nothing to show. Runs off the main thread.
     */
    static List<Video> load(Context c, String category) {
        String key = key(c);
        if (key.isEmpty()) return null;
        File dir = new File(c.getCacheDir(), "yt");
        dir.mkdirs();
        File cache = new File(dir, "list_" + (category.isEmpty() ? "all" : category) + ".json");
        boolean fresh = cache.isFile() && System.currentTimeMillis() - cache.lastModified() < FRESH_MS;
        String json = null;
        if (!fresh) {
            String url = "https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&chart=mostPopular"
                    + "&regionCode=FR&hl=fr&maxResults=20&key=" + Uri.encode(key)
                    + (category.isEmpty() ? "" : "&videoCategoryId=" + category);
            json = get(c, url);
            if (json != null) write(cache, json.getBytes(StandardCharsets.UTF_8));
        }
        if (json == null && cache.isFile()) json = new String(read(cache), StandardCharsets.UTF_8);
        return json == null ? null : parse(json);
    }

    private static List<Video> parse(String json) {
        List<Video> out = new ArrayList<>();
        try {
            JSONArray items = new JSONObject(json).optJSONArray("items");
            if (items == null) return null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                JSONObject sn = it.optJSONObject("snippet");
                JSONObject cd = it.optJSONObject("contentDetails");
                if (sn == null) continue;
                String live = sn.optString("liveBroadcastContent", "none");
                String dur = "live".equals(live) ? "EN DIRECT" : duration(cd == null ? "" : cd.optString("duration", ""));
                out.add(new Video(it.getString("id"), sn.optString("title", ""), sn.optString("channelTitle", ""), dur));
            }
        } catch (Exception e) {
            Log.w(TAG, "bad list", e);
            return null;
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Up to 3 videos for a search, published after publishedAfterMs (0: any time). A search costs 100 of the 10,000
     * daily units, so each result is kept a day under cacheKey. Off the main thread; null when nothing.
     */
    static List<Video> search(Context c, String query, long publishedAfterMs, String cacheKey) {
        String key = key(c);
        if (key.isEmpty()) return null;
        File dir = new File(c.getCacheDir(), "yt");
        dir.mkdirs();
        File cache = new File(dir, "search_" + cacheKey + ".json");
        String json = null;
        if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < 24L * 3600 * 1000) {
            json = new String(read(cache), StandardCharsets.UTF_8);
        } else {
            String url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=3"
                    + "&regionCode=FR&relevanceLanguage=fr&order=relevance&q=" + Uri.encode(query)
                    + (publishedAfterMs > 0 ? "&publishedAfter=" + java.time.Instant.ofEpochMilli(publishedAfterMs).toString() : "")
                    + "&key=" + Uri.encode(key);
            json = get(c, url);
            if (json != null) write(cache, json.getBytes(StandardCharsets.UTF_8));
        }
        if (json == null) return null;
        List<Video> out = new ArrayList<>();
        try {
            JSONArray items = new JSONObject(json).optJSONArray("items");
            if (items == null) return null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                JSONObject id = it.optJSONObject("id");
                JSONObject sn = it.optJSONObject("snippet");
                if (id == null || sn == null || !id.has("videoId")) continue;
                String title = android.text.Html.fromHtml(sn.optString("title", ""), android.text.Html.FROM_HTML_MODE_LEGACY).toString();
                out.add(new Video(id.getString("videoId"), title, sn.optString("channelTitle", ""), ""));
            }
        } catch (Exception e) {
            Log.w(TAG, "bad search", e);
            return null;
        }
        return out.isEmpty() ? null : out;
    }

    /** ISO 8601 "PT1H2M3S" as "1:02:03", "PT4M5S" as "4:05". */
    static String duration(String iso) {
        Matcher m = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matcher(iso);
        if (!m.matches()) return "";
        int h = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
        int mi = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        int s = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        if (h == 0 && mi == 0 && s == 0) return "";
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, mi, s)
                : String.format(java.util.Locale.ROOT, "%d:%02d", mi, s);
    }

    /**
     * The video's thumbnail cropped to 16:9 and scaled to w x h, from the disk cache or i.ytimg.com. Off the main
     * thread; null on failure.
     */
    static Bitmap thumbnail(Context c, String id, int w, int h) {
        File dir = new File(c.getCacheDir(), "ytimg");
        dir.mkdirs();
        File f = new File(dir, id + "_" + w + ".jpg");
        if (f.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return b;
        }
        byte[] data = bytes(c, "https://i.ytimg.com/vi/" + id + "/sddefault.jpg");
        if (data == null) data = bytes(c, "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg");
        if (data == null) return null;
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (src == null) return null;
        // sd/hq thumbnails are 4:3 with bars above and below a 16:9 picture: keep the middle.
        int sw = src.getWidth(), sh = src.getHeight();
        int ch = Math.min(sh, Math.round(sw * 9f / 16f));
        Bitmap crop = Bitmap.createBitmap(src, 0, (sh - ch) / 2, sw, ch);
        Bitmap out = Bitmap.createScaledBitmap(crop, w, h, true);
        if (crop != out && crop != src) crop.recycle();
        if (src != out) src.recycle();
        try (FileOutputStream o = new FileOutputStream(f)) {
            out.compress(Bitmap.CompressFormat.JPEG, 88, o);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** A large picture of the video for the full-screen backdrop (1280 wide when YouTube has it). Off the main thread. */
    static Bitmap backdrop(Context c, String id) {
        File dir = new File(c.getCacheDir(), "ytimg");
        dir.mkdirs();
        File f = new File(dir, id + "_bg.jpg");
        if (f.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return b;
        }
        byte[] data = bytes(c, "https://i.ytimg.com/vi/" + id + "/maxresdefault.jpg");
        Bitmap src = data == null ? null : BitmapFactory.decodeByteArray(data, 0, data.length);
        if (src == null) return thumbnail(c, id, 640, 360);
        Bitmap out = src.getWidth() > 1280 ? Bitmap.createScaledBitmap(src, 1280, Math.round(src.getHeight() * 1280f / src.getWidth()), true) : src;
        if (out != src) src.recycle();
        try (FileOutputStream o = new FileOutputStream(f)) {
            out.compress(Bitmap.CompressFormat.JPEG, 86, o);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** A rich colour of the picture for the background glow. */
    static int colour(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 8, 8, true);
        float best = -1;
        int pick = 0xFF555555;
        long r = 0, g = 0, bl = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int p = s.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                bl += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * hsv[2];
                if (score > best) {
                    best = score;
                    pick = p;
                }
            }
        }
        if (s != b) s.recycle();
        return best > 0.15f ? pick : Color.rgb((int) (r / 64), (int) (g / 64), (int) (bl / 64));
    }

    /** Opens the video in YouTube for TV, else anything that plays YouTube links. */
    static void open(Context c, String id) {
        Intent[] tries = {
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id)).setPackage(YOUTUBE_TV),
                new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + id)).setPackage(YOUTUBE_TV),
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id)),
        };
        for (Intent i : tries) {
            try {
                c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
            }
        }
    }

    // ---- network ----

    private static String get(Context c, String url) {
        byte[] b = bytes(c, url);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(Context c, String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(12000);
            // Lets a key restricted to this Android app work too.
            con.setRequestProperty("X-Android-Package", c.getPackageName());
            String cert = certSha1(c);
            if (cert != null) con.setRequestProperty("X-Android-Cert", cert);
            int code = con.getResponseCode();
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            byte[] body = in == null ? new byte[0] : readAll(in);
            if (code >= 400) {
                if (url.contains("googleapis")) {
                    Log.w(TAG, "HTTP " + code + ": " + new String(body, StandardCharsets.UTF_8));
                }
                return null;
            }
            return body;
        } catch (Exception e) {
            Log.w(TAG, "fetch failed: " + e);
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String cert;

    private static String certSha1(Context c) {
        if (cert != null) return cert;
        try {
            PackageManager pm = c.getPackageManager();
            Signature[] sigs;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = pi.signingInfo.getApkContentsSigners();
            } else {
                @SuppressWarnings("deprecation")
                PackageInfo pi = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES);
                sigs = pi.signatures;
            }
            byte[] d = MessageDigest.getInstance("SHA-1").digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format(java.util.Locale.ROOT, "%02X", x));
            cert = sb.toString();
        } catch (Exception e) {
            cert = null;
        }
        return cert;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toByteArray();
    }

    private static void write(File f, byte[] data) {
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(data);
        } catch (Exception ignored) {
        }
    }

    private static byte[] read(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return readAll(in);
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
