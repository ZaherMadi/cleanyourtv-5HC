package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;

/**
 * The weather where the TV is, located from its public IP (ipwho.is, no key), from Open Weather (RapidAPI).
 * Shown as a small icon and temperature next to the clock. Location kept a day, weather an hour.
 */
final class WeatherFeed {
    static final String HOST = "open-weather13.p.rapidapi.com";

    private WeatherFeed() {
    }

    /** Fills tv with "⛅ 6°" in the background; hides it when there is nothing to show. */
    static void into(TextView tv) {
        final Context app = tv.getContext().getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            final String[] w = current(app);
            main.post(() -> {
                if (w == null) {
                    tv.setVisibility(android.view.View.GONE);
                    return;
                }
                tv.setText(w[0]);
                tv.setContentDescription(w[1]);
                tv.setVisibility(android.view.View.VISIBLE);
            });
        }, "weather").start();
    }

    /** {text, description}, or null. Off the main thread. */
    static synchronized String[] current(Context c) {
        java.util.Map<String, String> headers = Net.rapid(c, HOST);
        if (headers == null) return null;
        try {
            String loc = Net.json(c, "ip_location.json", 24L * 3600 * 1000, "https://ipwho.is/?fields=success,latitude,longitude,city", null);
            if (loc == null) return null;
            JSONObject l = new JSONObject(loc);
            if (!l.optBoolean("success", true) || !l.has("latitude")) return null;
            String lat = String.format(Locale.ROOT, "%.3f", l.getDouble("latitude"));
            String lon = String.format(Locale.ROOT, "%.3f", l.getDouble("longitude"));
            String json = Net.json(c, "weather.json", 3600L * 1000,
                    "https://" + HOST + "/latlon?latitude=" + lat + "&longitude=" + lon + "&lang=FR", headers);
            if (json == null) return null;
            JSONObject w = new JSONObject(json);
            JSONObject main = w.getJSONObject("main");
            double k = main.getDouble("temp");
            // This API answers in kelvin; some of its mirrors in °C already.
            long t = Math.round(k > 150 ? k - 273.15 : k);
            JSONObject cond = w.getJSONArray("weather").getJSONObject(0);
            String icon = cond.optString("icon", "01d");
            String desc = cond.optString("description", "");
            String city = w.optString("name", l.optString("city", ""));
            return new String[]{emoji(icon) + "  " + t + "°", city + " : " + desc + ", " + t + " degrés"};
        } catch (Exception e) {
            return null;
        }
    }

    static String emoji(String icon) {
        String code = icon.length() >= 2 ? icon.substring(0, 2) : "01";
        boolean night = icon.endsWith("n");
        switch (code) {
            case "01": return night ? "🌙" : "☀️";
            case "02": return night ? "☁️" : "⛅";
            case "03":
            case "04": return "☁️";
            case "09": return "🌧️";
            case "10": return night ? "🌧️" : "🌦️";
            case "11": return "⛈️";
            case "13": return "❄️";
            case "50": return "🌫️";
            default: return "🌡️";
        }
    }
}
