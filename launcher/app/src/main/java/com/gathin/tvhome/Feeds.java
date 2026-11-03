package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The online "volets" shared by both homes: YouTube, films and series (Streaming Availability) and sport (SportAPI).
 * Each source gives sections (a row each) of cards; the homes draw them, the source makes their pictures and opens them.
 */
final class Feeds {
    static final int YOUTUBE = 0, SHOWS = 1, SPORT = 2;

    private Feeds() {
    }

    static final class Card {
        final int kind;
        final String id;
        String title = "", sub = "", badge = "";
        int badgeColour = 0xCC000000;
        String image, backdrop, link, pkg;
        /** App whose icon stands beside the hero's meta line (films and series). */
        String iconPkg;
        // Sport
        String homeId, awayId, homeCode, awayCode, centre = "", tournament = "";
        int tint = 0xFF1B3A5C;
        boolean live, finished;
        String homeName = "", awayName = "";
        long startMs;

        Card(int kind, String id) {
            this.kind = kind;
            this.id = id;
        }

        /** Line under the title in the big hero (Mode simple). */
        String heroMeta() {
            if (kind == YOUTUBE) return badge.isEmpty() ? sub : sub + "  ·  " + badge;
            return sub;
        }
    }

    static final class Section {
        /** Short name, after the volet's name ("YouTube · Musique"). */
        final String name;
        /** Full row title for the Mode personnalisé ("Musique sur YouTube"). */
        final String heading;
        final List<Card> cards;

        Section(String name, String heading, List<Card> cards) {
            this.name = name;
            this.heading = heading;
            this.cards = cards;
        }
    }

    interface Source {
        String title();

        /** Off the main thread. Empty when there is no key, no network and no cache. */
        List<Section> load(Context c);

        /** The card's picture at w x h. Off the main thread. */
        Bitmap card(Context c, Card k, int w, int h);

        /** A large picture for the full-screen backdrop. Off the main thread. */
        Bitmap backdrop(Context c, Card k);

        void open(Context c, Card k);
    }

    static List<Source> all() {
        return new ArrayList<>(Arrays.asList(new YouTubeSource(), new StreamingFeed(), new SportFeed()));
    }

    /** Opens a link in that app when it can, else just opens the app. */
    static void openIn(Context c, String link, String pkg) {
        if (link != null && !link.isEmpty()) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (pkg != null) i.setPackage(pkg);
                c.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        if (pkg == null) return;
        PackageManager pm = c.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return;
        try {
            c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
