package com.mimikm.homecanvas;

import android.content.Context;
import android.content.SharedPreferences;

/** Small wrapper around SharedPreferences shared by the screensaver, preview and settings. */
final class Prefs {
    static final String URL = "url";
    static final String TOKEN = "token";
    static final String RELOAD_MIN = "reload_minutes";
    static final String WIDTH = "viewport_width";
    static final String BRIGHT = "bright";
    static final String INTERACTIVE = "interactive";
    static final String IGNORE_SSL = "ignore_ssl";

    private final SharedPreferences sp;

    Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    String url() { return sp.getString(URL, "").trim(); }
    String token() { return sp.getString(TOKEN, "").trim(); }
    int reloadMinutes() { return sp.getInt(RELOAD_MIN, 0); }
    int viewportWidth() { return sp.getInt(WIDTH, 0); }
    boolean bright() { return sp.getBoolean(BRIGHT, true); }
    boolean interactive() { return sp.getBoolean(INTERACTIVE, false); }
    boolean ignoreSsl() { return sp.getBoolean(IGNORE_SSL, false); }

    SharedPreferences.Editor edit() { return sp.edit(); }
}
