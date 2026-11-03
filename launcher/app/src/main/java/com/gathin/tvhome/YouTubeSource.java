package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

/** YouTube as a volet: the rows of {@link YouTubeFeed}. */
final class YouTubeSource implements Feeds.Source {
    private static final String[][] NAMES = {
            {"Tendances", "Tendances sur YouTube"},
            {"Musique", "Musique sur YouTube"},
            {"Jeux vidéo", "Jeux vidéo sur YouTube"},
            {"Sport", "Sport sur YouTube"},
    };

    /**
     * Two or three YouTube videos about a match: the highlights once it is over ("résumé", published after the
     * kick-off), the build-up before it. Nothing while it is being played. Off the main thread.
     */
    static List<Feeds.Card> highlights(Context c, Feeds.Card match) {
        List<Feeds.Card> out = new ArrayList<>();
        if (match.kind != Feeds.SPORT || match.live || match.homeName.isEmpty()) return out;
        String q;
        long after;
        if (match.finished) {
            q = match.homeName + " " + match.awayName + " résumé";
            after = match.startMs;
        } else {
            q = match.homeName + " " + match.awayName + " avant-match";
            after = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        }
        List<YouTubeFeed.Video> videos = YouTubeFeed.search(c, q, after, "m" + match.id);
        if (videos == null) return out;
        for (YouTubeFeed.Video v : videos) {
            Feeds.Card k = new Feeds.Card(Feeds.YOUTUBE, v.id);
            k.title = v.title;
            k.sub = v.channel;
            out.add(k);
        }
        return out;
    }

    @Override
    public String title() {
        return "YouTube";
    }

    @Override
    public List<Feeds.Section> load(Context c) {
        List<Feeds.Section> out = new ArrayList<>();
        for (int r = 0; r < YouTubeFeed.ROWS.length; r++) {
            List<YouTubeFeed.Video> videos = YouTubeFeed.load(c, YouTubeFeed.ROWS[r][1]);
            if (videos == null || videos.isEmpty()) continue;
            List<Feeds.Card> cards = new ArrayList<>();
            for (YouTubeFeed.Video v : videos) {
                Feeds.Card k = new Feeds.Card(Feeds.YOUTUBE, v.id);
                k.title = v.title;
                k.sub = v.channel;
                k.badge = v.duration;
                if ("EN DIRECT".equals(v.duration)) k.badgeColour = 0xE6CC0000;
                cards.add(k);
            }
            out.add(new Feeds.Section(NAMES[r][0], NAMES[r][1], cards));
        }
        return out;
    }

    @Override
    public Bitmap card(Context c, Feeds.Card k, int w, int h) {
        return YouTubeFeed.thumbnail(c, k.id, w, h);
    }

    @Override
    public Bitmap backdrop(Context c, Feeds.Card k) {
        return YouTubeFeed.backdrop(c, k.id);
    }

    @Override
    public void open(Context c, Feeds.Card k) {
        YouTubeFeed.open(c, k.id);
    }
}
