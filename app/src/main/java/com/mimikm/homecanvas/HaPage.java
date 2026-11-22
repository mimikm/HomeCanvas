package com.mimikm.homecanvas;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * A configured WebView showing the Home Assistant dashboard, with periodic reload,
 * automatic retry when HA is unreachable, optional token login and layout width override.
 * Used by both the screensaver (DreamService) and the preview / login activity.
 */
final class HaPage {
    private static final String TAG = "HomeCanvas";
    private static final long RETRY_MS = 30_000;

    private final WebView web;
    private final Prefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean tokenAttempted;
    private boolean running;

    private final Runnable reloadTask = new Runnable() {
        @Override public void run() {
            if (!running) return;
            Log.d(TAG, "Periodic reload");
            load();
            scheduleReload();
        }
    };

    private final Runnable retryTask = () -> { if (running) load(); };

    @SuppressLint("SetJavaScriptEnabled")
    HaPage(Context ctx, Prefs prefs) {
        this.prefs = prefs;
        web = new WebView(ctx);
        web.setBackgroundColor(Color.BLACK);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new Client());
    }

    WebView view() { return web; }

    void start() {
        running = true;
        tokenAttempted = false;
        load();
        scheduleReload();
    }

    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        web.stopLoading();
    }

    void destroy() {
        stop();
        web.loadUrl("about:blank");
        web.destroy();
    }

    boolean goBack() {
        if (web.canGoBack()) { web.goBack(); return true; }
        return false;
    }

    private void load() {
        handler.removeCallbacks(retryTask);
        String url = prefs.url();
        if (url.isEmpty()) {
            web.loadDataWithBaseURL(null,
                "<html><body style='background:#000;color:#9aa4af;font-family:sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>"
                + "<div style='text-align:center'><h2 style='color:#fff'>HomeCanvas</h2>"
                + "Open the <b>HomeCanvas</b> app to set your dashboard URL.</div></body></html>",
                "text/html", "utf-8", null);
            return;
        }
        web.loadUrl(url);
    }

    private void scheduleReload() {
        handler.removeCallbacks(reloadTask);
        int min = prefs.reloadMinutes();
        if (min > 0) handler.postDelayed(reloadTask, min * 60_000L);
    }

    private void scheduleRetry() {
        if (!running) return;
        handler.removeCallbacks(retryTask);
        handler.postDelayed(retryTask, RETRY_MS);
    }

    private static String origin(String url) {
        Uri u = Uri.parse(url);
        if (u.getScheme() == null || u.getAuthority() == null) return "";
        return u.getScheme() + "://" + u.getAuthority();
    }

    /** Override the page viewport so HA lays out as if the screen were N CSS px wide. */
    private void applyViewport(WebView view) {
        int w = prefs.viewportWidth();
        if (w <= 0) return;
        view.evaluateJavascript(
            "(function(w){var m=document.querySelector('meta[name=viewport]');"
            + "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}"
            + "var c='width='+w;if(m.content!==c){m.content=c;window.dispatchEvent(new Event('resize'));}})(" + w + ")",
            null);
    }

    /**
     * If a long-lived access token is configured, store it in HA's frontend auth storage
     * (localStorage "hassTokens") so the dashboard opens without the login page.
     */
    private void injectToken(WebView view, String pageUrl) {
        String token = prefs.token().replaceAll("[^A-Za-z0-9._\\-]", "");
        if (token.isEmpty() || tokenAttempted) return;
        String haOrigin = origin(prefs.url());
        if (haOrigin.isEmpty() || pageUrl == null || !pageUrl.startsWith(haOrigin)) return;
        tokenAttempted = true;
        String js = "(function(){try{"
            + "var t=JSON.parse(localStorage.getItem('hassTokens')||'null');"
            + "if(t&&t.access_token==='" + token + "')return 'ok';"
            + "localStorage.setItem('hassTokens',JSON.stringify({access_token:'" + token + "',"
            + "token_type:'Bearer',expires_in:1800,hassUrl:location.origin,"
            + "clientId:location.origin+'/',expires:9999999999999,refresh_token:''}));"
            + "return 'set';}catch(e){return 'err:'+e;}})()";
        view.evaluateJavascript(js, result -> {
            Log.d(TAG, "Token injection: " + result);
            if ("\"set\"".equals(result)) view.loadUrl(prefs.url());
        });
    }

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false; // keep everything inside the WebView
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            applyViewport(view);
            injectToken(view, url);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                Log.w(TAG, "Load error " + error.getErrorCode() + ": " + error.getDescription());
                showMessage(view, "Can't reach Home Assistant (" + error.getDescription() + "). Retrying in 30s…");
                scheduleRetry();
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() >= 500) {
                showMessage(view, "Home Assistant returned HTTP " + response.getStatusCode() + ". Retrying in 30s…");
                scheduleRetry();
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler h, SslError error) {
            if (prefs.ignoreSsl()) {
                h.proceed();
            } else {
                h.cancel();
                showMessage(view, "HTTPS certificate error. Enable \"Ignore HTTPS certificate errors\" in settings if HA uses a self-signed cert.");
            }
        }

        private void showMessage(WebView view, String msg) {
            String safe = msg.replace("&", "&amp;").replace("<", "&lt;");
            view.loadDataWithBaseURL(null,
                "<html><body style='background:#000;color:#9aa4af;font-family:sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>"
                + "<div style='max-width:70%;text-align:center'>" + safe + "</div></body></html>",
                "text/html", "utf-8", null);
        }
    }
}
