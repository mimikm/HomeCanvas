package com.mimikm.homecanvas;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings screen (TV launcher entry + the screensaver's settings activity).
 *
 * Can also be configured from a computer with adb, which saves typing a long token with the remote:
 *   adb shell am start -n com.mimikm.homecanvas/.SettingsActivity \
 *     --es url "http://192.168.1.10:8123/lovelace/screensaver" --es token "eyJ..." \
 *     --ei reload 30 --ei width 1280 --ez bright true --ez interactive false --ez ignore_ssl false
 */
public class SettingsActivity extends Activity {
    private EditText url, token, reload, width;
    private CheckBox bright, interactive, ignoreSsl;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);

        url = findViewById(R.id.url);
        token = findViewById(R.id.token);
        reload = findViewById(R.id.reload);
        width = findViewById(R.id.width);
        bright = findViewById(R.id.bright);
        interactive = findViewById(R.id.interactive);
        ignoreSsl = findViewById(R.id.ignoreSsl);

        if (applyIntentExtras(getIntent())) {
            Toast.makeText(this, "Settings received via adb and saved", Toast.LENGTH_LONG).show();
        }
        loadFields();

        findViewById(R.id.save).setOnClickListener(v -> {
            save();
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.login).setOnClickListener(v -> open(WebActivity.MODE_LOGIN));
        findViewById(R.id.preview).setOnClickListener(v -> open(WebActivity.MODE_PREVIEW));
        findViewById(R.id.clear).setOnClickListener(v -> {
            WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            Toast.makeText(this, "Login data cleared", Toast.LENGTH_SHORT).show();
        });

        String component = new ComponentName(this, HaDreamService.class).flattenToShortString();
        ((TextView) findViewById(R.id.help)).setText(
            "Set as screensaver (from a computer, with ADB/developer options enabled on the TV):\n\n"
            + "  adb connect <tv-ip>\n"
            + "  adb shell settings put secure screensaver_components " + component + "\n"
            + "  adb shell settings put secure screensaver_enabled 1\n"
            + "Test now:\n"
            + "  adb shell am start -n com.android.systemui/.Somnambulator\n\n"
            + "Revert to Google TV Ambient mode:\n"
            + "  adb shell settings delete secure screensaver_components");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (applyIntentExtras(intent)) {
            loadFields();
            Toast.makeText(this, "Settings received via adb and saved", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void open(String mode) {
        save();
        if (prefs.url().isEmpty()) {
            Toast.makeText(this, "Enter the dashboard URL first", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, WebActivity.class).putExtra(WebActivity.EXTRA_MODE, mode));
    }

    private void loadFields() {
        url.setText(prefs.url());
        token.setText(prefs.token());
        reload.setText(String.valueOf(prefs.reloadMinutes()));
        width.setText(String.valueOf(prefs.viewportWidth()));
        bright.setChecked(prefs.bright());
        interactive.setChecked(prefs.interactive());
        ignoreSsl.setChecked(prefs.ignoreSsl());
    }

    private void save() {
        prefs.edit()
            .putString(Prefs.URL, normaliseUrl(url.getText().toString()))
            .putString(Prefs.TOKEN, token.getText().toString().trim())
            .putInt(Prefs.RELOAD_MIN, parseInt(reload.getText().toString()))
            .putInt(Prefs.WIDTH, parseInt(width.getText().toString()))
            .putBoolean(Prefs.BRIGHT, bright.isChecked())
            .putBoolean(Prefs.INTERACTIVE, interactive.isChecked())
            .putBoolean(Prefs.IGNORE_SSL, ignoreSsl.isChecked())
            .apply();
        url.setText(prefs.url());
    }

    /** Returns true if any settings were supplied as intent extras (adb). */
    private boolean applyIntentExtras(Intent i) {
        if (i == null || i.getExtras() == null) return false;
        Bundle b = i.getExtras();
        boolean any = false;
        android.content.SharedPreferences.Editor e = prefs.edit();
        if (b.containsKey("url")) { e.putString(Prefs.URL, normaliseUrl(b.getString("url", ""))); any = true; }
        if (b.containsKey("token")) { e.putString(Prefs.TOKEN, b.getString("token", "").trim()); any = true; }
        if (b.containsKey("reload")) { e.putInt(Prefs.RELOAD_MIN, Math.max(0, b.getInt("reload"))); any = true; }
        if (b.containsKey("width")) { e.putInt(Prefs.WIDTH, Math.max(0, b.getInt("width"))); any = true; }
        if (b.containsKey("bright")) { e.putBoolean(Prefs.BRIGHT, b.getBoolean("bright")); any = true; }
        if (b.containsKey("interactive")) { e.putBoolean(Prefs.INTERACTIVE, b.getBoolean("interactive")); any = true; }
        if (b.containsKey("ignore_ssl")) { e.putBoolean(Prefs.IGNORE_SSL, b.getBoolean("ignore_ssl")); any = true; }
        if (any) e.apply();
        return any;
    }

    private void updateStatus() {
        TextView status = findViewById(R.id.status);
        String ours = new ComponentName(this, HaDreamService.class).flattenToString();
        String current = null;
        try {
            current = Settings.Secure.getString(getContentResolver(), "screensaver_components");
        } catch (Exception ignored) {
            // Newer Android may block reading this hidden setting.
        }
        if (current == null) {
            status.setText("Screensaver status: unknown (use the adb commands below to set it)");
        } else if (current.contains(ours) || current.contains(".HaDreamService")) {
            status.setText("✓ HomeCanvas is the active screensaver");
        } else {
            status.setText("Not the active screensaver yet – current: " + current);
        }
    }

    private static String normaliseUrl(String s) {
        s = s.trim();
        if (!s.isEmpty() && !s.contains("://")) s = "http://" + s;
        return s;
    }

    private static int parseInt(String s) {
        try { return Math.max(0, Integer.parseInt(s.trim())); } catch (NumberFormatException e) { return 0; }
    }
}
