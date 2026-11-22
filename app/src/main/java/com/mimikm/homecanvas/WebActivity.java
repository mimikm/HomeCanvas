package com.mimikm.homecanvas;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Full-screen dashboard outside the screensaver.
 * MODE_LOGIN: remote controls the page so you can log in to HA once (login is shared with the screensaver).
 * MODE_PREVIEW: behaves like the screensaver – any key closes it.
 */
public class WebActivity extends Activity {
    static final String EXTRA_MODE = "mode";
    static final String MODE_LOGIN = "login";
    static final String MODE_PREVIEW = "preview";

    private HaPage page;
    private boolean login;
    private final long openedAt = System.currentTimeMillis();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        login = MODE_LOGIN.equals(getIntent().getStringExtra(EXTRA_MODE));
        page = new HaPage(this, new Prefs(this));
        setContentView(page.view());
        page.view().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        page.view().requestFocus();
    }

    @Override protected void onResume() { super.onResume(); page.start(); }
    @Override protected void onPause() { page.stop(); super.onPause(); }
    @Override protected void onDestroy() { page.destroy(); super.onDestroy(); }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (login) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                if (!page.goBack()) finish();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        // Preview: like the real screensaver, any key dismisses it
        // (ignoring the first second so the button press that opened it doesn't close it).
        if (event.getAction() == KeyEvent.ACTION_UP
                && System.currentTimeMillis() - openedAt > 1000) finish();
        return true;
    }
}
