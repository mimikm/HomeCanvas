package com.mimikm.homecanvas;

import android.service.dreams.DreamService;
import android.view.KeyEvent;

/** The screensaver itself: shows the HA dashboard full screen while the TV is idle. */
public class HaDreamService extends DreamService {
    private HaPage page;
    private boolean interactive;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Prefs prefs = new Prefs(this);
        interactive = prefs.interactive();
        setInteractive(interactive);
        setFullscreen(true);
        setScreenBright(prefs.bright());
        page = new HaPage(this, prefs);
        setContentView(page.view());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (page != null) {
            page.start();
            if (interactive) page.view().requestFocus();
        }
    }

    @Override
    public void onDreamingStopped() {
        if (page != null) page.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (page != null) { page.destroy(); page = null; }
        super.onDetachedFromWindow();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // In interactive mode the remote drives the dashboard; Back / Home still exit.
        if (interactive && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
