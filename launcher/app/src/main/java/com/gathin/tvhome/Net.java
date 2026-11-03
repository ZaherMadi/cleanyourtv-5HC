package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** Small HTTP and disk-cache helpers for the home's online rows (YouTube, films and series, sport, weather). */
final class Net {
    private static final String TAG = "Home/Net";

    private Net() {
    }

    /** RapidAPI headers for that API host, or null while no RapidAPI key is set (see {@link Keys}). */
    static Map<String, String> rapid(Context c, String host) {
        String key = Keys.rapid(c);
        if (key.isEmpty()) return null;
        Map<String, String> h = new HashMap<>();
        h.put("X-RapidAPI-Key", key);
        h.put("X-RapidAPI-Host", host);
        return h;
    }

    /** The body, or null on any error (logged). Off the main thread. */
    static byte[] get(String url, Map<String, String> headers) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(15000);
            con.setRequestProperty("User-Agent", "CleanYourTV-Home/1.0");
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) con.setRequestProperty(e.getKey(), e.getValue());
            int code = con.getResponseCode();
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            byte[] body = in == null ? new byte[0] : readAll(in);
            if (code >= 400) {
                String host = new URL(url).getHost();
                Log.w(TAG, host + " HTTP " + code + ": " + new String(body, 0, Math.min(body.length, 300), StandardCharsets.UTF_8));
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

    /**
     * JSON from the cache while it is younger than freshMs, else from the network (then cached), else the stale
     * cache. Null when there is nothing at all.
     */
    static String json(Context c, String name, long freshMs, String url, Map<String, String> headers) {
        File dir = new File(c.getCacheDir(), "feeds");
        dir.mkdirs();
        File f = new File(dir, name);
        boolean fresh = f.isFile() && System.currentTimeMillis() - f.lastModified() < freshMs;
        if (!fresh && url != null) {
            byte[] b = get(url, headers);
            if (b != null && b.length > 0) {
                write(f, b);
                return new String(b, StandardCharsets.UTF_8);
            }
        }
        if (!f.isFile()) return null;
        byte[] b = read(f);
        return b.length == 0 ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** A picture cropped to fill w x h (centre), from the disk cache or the network. Off the main thread. */
    static Bitmap image(Context c, String url, Map<String, String> headers, int w, int h) {
        return image(c, url, headers, w, h, false);
    }

    /** fit: the whole picture inside w x h, on transparent margins (logos), instead of cropped to fill it. */
    static Bitmap image(Context c, String url, Map<String, String> headers, int w, int h, boolean fit) {
        if (url == null || url.isEmpty()) return null;
        File dir = new File(c.getCacheDir(), "img");
        dir.mkdirs();
        File f = new File(dir, sha1(url.replaceAll("\\?.*$", "")) + "_" + w + "x" + h + (fit ? "_fit" : "") + ".png");
        if (f.isFile()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return b;
        }
        byte[] data = get(url, headers);
        if (data == null) return null;
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (src == null) return null;
        Bitmap out = fit ? contain(src, w, h) : cover(src, w, h);
        if (out != src) src.recycle();
        try (FileOutputStream o = new FileOutputStream(f)) {
            // PNG keeps team logos' transparency; JPEG-like pictures stay small enough at these sizes.
            out.compress(out.hasAlpha() ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 88, o);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Scales src to fit inside w x h, centred on a transparent canvas. */
    static Bitmap contain(Bitmap src, int w, int h) {
        float s = Math.min(w / (float) src.getWidth(), h / (float) src.getHeight());
        int dw = Math.max(1, Math.round(src.getWidth() * s)), dh = Math.max(1, Math.round(src.getHeight() * s));
        Bitmap scaled = Bitmap.createScaledBitmap(src, dw, dh, true);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new android.graphics.Canvas(out).drawBitmap(scaled, (w - dw) / 2f, (h - dh) / 2f,
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        if (scaled != src) scaled.recycle();
        return out;
    }

    /** Scales and crops src to fill w x h. */
    static Bitmap cover(Bitmap src, int w, int h) {
        float s = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        int sw = Math.round(w / s), sh = Math.round(h / s);
        int x = Math.max(0, (src.getWidth() - sw) / 2), y = Math.max(0, (src.getHeight() - sh) / 2);
        sw = Math.min(sw, src.getWidth() - x);
        sh = Math.min(sh, src.getHeight() - y);
        Bitmap crop = Bitmap.createBitmap(src, x, y, sw, sh);
        Bitmap out = Bitmap.createScaledBitmap(crop, w, h, true);
        if (crop != out && crop != src) crop.recycle();
        return out;
    }

    static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format(java.util.Locale.ROOT, "%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
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
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
