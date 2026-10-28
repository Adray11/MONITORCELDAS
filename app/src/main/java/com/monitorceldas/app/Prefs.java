package com.monitorceldas.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Ajustes persistentes de la app. */
final class Prefs {
    static final String DL_URL = "dl_url";
    static final String UL_URL = "ul_url";
    static final String LAT_URL = "lat_url";
    static final String PING_HOST = "ping_host";
    static final String TEST_DUR = "test_dur";
    static final String STREAMS = "streams";
    static final String AUTO_ON = "auto_on";
    static final String AUTO_DIST = "auto_dist";
    static final String AUTO_MIN = "auto_min";
    static final String LOG_MS = "log_ms";
    static final String KML_ACC = "kml_acc";
    static final String SEGMENTS = "segments";
    static final String POINTS = "points";

    static final String DEF_DL = "https://speed.cloudflare.com/__down?bytes=100000000";
    static final String DEF_UL = "https://speed.cloudflare.com/__up";
    static final String DEF_LAT = "https://speed.cloudflare.com/__down?bytes=0";
    static final String DEF_PING = "1.1.1.1";
    static final String DEF_SEGMENTS = "1 Interior edificio|2 Lobby / acceso|3 Av. Nicolas Arriola - Av. Canada|"
            + "4 Paradero Metropolitano|5 Bus Via Expresa|6 Superficie Est. Central|7 Est. Central subterranea";
    static final String DEF_POINTS = "IN-01|IN-02|IN-03|IN-04|IN-05|IN-06|IN-07|IN-08|IN-09|IN-10|OUT-01|OUT-02";

    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("ajustes", Context.MODE_PRIVATE);
    }

    static String str(Context c, String k, String def) {
        String v = sp(c).getString(k, def);
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }

    static int num(Context c, String k, int def) {
        try {
            return Integer.parseInt(str(c, k, Integer.toString(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static boolean bool(Context c, String k, boolean def) {
        return sp(c).getBoolean(k, def);
    }

    static void put(Context c, String k, String v) {
        sp(c).edit().putString(k, v).apply();
    }

    static void putBool(Context c, String k, boolean v) {
        sp(c).edit().putBoolean(k, v).apply();
    }

    static int logMs(Context c) {
        return Math.max(500, Math.min(60000, num(c, LOG_MS, 1000)));
    }

    static int testDur(Context c) {
        return Math.max(3, Math.min(60, num(c, TEST_DUR, 10)));
    }

    static int streams(Context c) {
        return Math.max(1, Math.min(16, num(c, STREAMS, 4)));
    }

    static String[] list(Context c, String k, String def) {
        String[] parts = str(c, k, def).split("\\|");
        String[] out = new String[parts.length + 1];
        out[0] = "—";
        for (int i = 0; i < parts.length; i++) out[i + 1] = parts[i].trim();
        return out;
    }
}
