package com.mimikm.homecanvas;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny web server that serves a phone-friendly setup page while the settings screen is open.
 * The TV shows a QR code with http://<tv-ip>:8765/?k=<random key>; requests without the key are refused.
 */
final class ConfigServer {
    interface Listener {
        /** Called on the server thread after settings were saved from the phone. */
        void onSaved(boolean preview);
    }

    static final int PORT = 8765;
    private static final String TAG = "HomeCanvas";

    private final Prefs prefs;
    private final Listener listener;
    private final String key;
    private volatile boolean running;
    private volatile ServerSocket server;

    ConfigServer(Prefs prefs, Listener listener) {
        this.prefs = prefs;
        this.listener = listener;
        byte[] b = new byte[6];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        key = sb.toString();
    }

    String url(String ip) {
        return "http://" + ip + ":" + PORT + "/?k=" + key;
    }

    void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::run, "HomeCanvasConfig");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private void run() {
        ServerSocket ss = null;
        // The previous server may still be releasing the port (e.g. after returning from preview).
        for (int attempt = 0; attempt < 10 && running && ss == null; attempt++) {
            try {
                ServerSocket candidate = new ServerSocket();
                candidate.setReuseAddress(true);
                candidate.bind(new InetSocketAddress(PORT));
                ss = candidate;
            } catch (IOException e) {
                try { Thread.sleep(500); } catch (InterruptedException ie) { return; }
            }
        }
        if (ss == null) { Log.w(TAG, "Config server could not bind port " + PORT); return; }
        server = ss;
        if (!running) { stop(); return; }
        Log.d(TAG, "Config server listening on " + PORT);
        while (running) {
            try (Socket c = ss.accept()) {
                c.setSoTimeout(10_000);
                handle(c);
            } catch (IOException e) {
                if (!running) break;
                Log.w(TAG, "Config request failed: " + e);
            }
        }
    }

    // ---------- HTTP ----------

    private void handle(Socket c) throws IOException {
        InputStream in = c.getInputStream();
        OutputStream out = c.getOutputStream();

        String requestLine = readLine(in);
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return;
        String method = parts[0];
        String target = parts[1];

        int length = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            String l = line.toLowerCase();
            if (l.startsWith("content-length:")) {
                try { length = Integer.parseInt(line.substring(15).trim()); } catch (NumberFormatException ignored) { }
            }
        }
        length = Math.max(0, Math.min(length, 32 * 1024));
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) break;
            read += n;
        }

        String path = target;
        String query = "";
        int qi = target.indexOf('?');
        if (qi >= 0) { path = target.substring(0, qi); query = target.substring(qi + 1); }
        Map<String, String> q = parseForm(query);

        if (path.equals("/favicon.ico")) { respond(out, 404, "text/plain", "not found"); return; }
        if (!key.equals(q.get("k"))) {
            respond(out, 403, "text/html", page("<h1>Link expired</h1><p>Open <b>HomeCanvas</b> on the TV and scan the QR code again.</p>"));
            return;
        }

        if ("GET".equals(method) && path.equals("/")) {
            respond(out, 200, "text/html", form(null));
        } else if ("POST".equals(method) && path.equals("/save")) {
            Map<String, String> f = parseForm(new String(body, 0, read, StandardCharsets.UTF_8));
            apply(f);
            boolean preview = f.containsKey("preview");
            respond(out, 200, "text/html", form(preview
                ? "Saved ✓ — preview is opening on the TV (press any remote button to close it)."
                : "Saved ✓"));
            listener.onSaved(preview);
        } else {
            respond(out, 404, "text/plain", "not found");
        }
    }

    private void apply(Map<String, String> f) {
        SharedPreferences.Editor e = prefs.edit();
        e.putString(Prefs.URL, SettingsActivity.normaliseUrl(get(f, "url")));
        String token = get(f, "token").trim();
        if (f.containsKey("clear_token")) e.putString(Prefs.TOKEN, "");
        else if (!token.isEmpty()) e.putString(Prefs.TOKEN, token);
        e.putInt(Prefs.RELOAD_MIN, SettingsActivity.parseInt(get(f, "reload")));
        e.putInt(Prefs.WIDTH, SettingsActivity.parseInt(get(f, "width")));
        e.putBoolean(Prefs.BRIGHT, f.containsKey("bright"));
        e.putBoolean(Prefs.INTERACTIVE, f.containsKey("interactive"));
        e.putBoolean(Prefs.IGNORE_SSL, f.containsKey("ignore_ssl"));
        e.commit();
    }

    private String form(String message) {
        String k = key;
        StringBuilder h = new StringBuilder();
        h.append("<h1>HomeCanvas setup</h1>");
        if (message != null) h.append("<div class=ok>").append(esc(message)).append("</div>");
        h.append("<form method=post action='/save?k=").append(k).append("'>");
        h.append("<label>Dashboard URL</label>")
         .append("<input name=url type=url inputmode=url autocapitalize=off autocorrect=off placeholder='http://192.168.1.10:8123/lovelace/tv' value='")
         .append(esc(prefs.url())).append("'>");
        h.append("<label>Long-lived access token</label>")
         .append("<input name=token type=password autocomplete=off placeholder='")
         .append(prefs.token().isEmpty() ? "Optional – paste token here" : "Token saved – leave blank to keep it")
         .append("'>");
        if (!prefs.token().isEmpty()) {
            h.append("<label class=chk><input type=checkbox name=clear_token> Remove saved token</label>");
        }
        h.append("<p class=hint>Create one in Home Assistant: your Profile → Security → Long-lived access tokens. "
            + "Without a token, use “Open HA / log in” on the TV once.</p>");
        h.append("<div class=row><div><label>Reload every (min, 0 = never)</label><input name=reload type=number min=0 value='")
         .append(prefs.reloadMinutes()).append("'></div>");
        h.append("<div><label>Layout width px (0 = default)</label><input name=width type=number min=0 step=10 value='")
         .append(prefs.viewportWidth()).append("'></div></div>");
        h.append("<p class=hint>Layout width 1280 or 1920 gives a desktop-style dashboard on the TV.</p>");
        h.append(check("bright", "Keep screen at full brightness", prefs.bright()));
        h.append(check("interactive", "Interactive (remote controls the dashboard, Back exits)", prefs.interactive()));
        h.append(check("ignore_ssl", "Ignore HTTPS certificate errors (self-signed)", prefs.ignoreSsl()));
        h.append("<button type=submit>Save</button>");
        h.append("<button type=submit name=preview value=1 class=sec>Save &amp; preview on TV</button>");
        h.append("</form>");
        return page(h.toString());
    }

    private static String check(String name, String label, boolean on) {
        return "<label class=chk><input type=checkbox name=" + name + (on ? " checked" : "") + "> " + esc(label) + "</label>";
    }

    private static String page(String content) {
        return "<!doctype html><html><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<title>HomeCanvas setup</title><style>"
            + "body{font-family:system-ui,-apple-system,sans-serif;background:#111418;color:#e6edf3;margin:0;padding:20px;max-width:560px;margin:auto}"
            + "h1{font-size:22px;margin:4px 0 16px}label{display:block;margin:14px 0 6px;font-size:14px;color:#c9d1d9}"
            + "input[type=url],input[type=password],input[type=number]{width:100%;box-sizing:border-box;padding:12px;border-radius:8px;"
            + "border:1px solid #444b55;background:#1e2329;color:#fff;font-size:16px}"
            + ".row{display:flex;gap:12px}.row>div{flex:1}"
            + ".chk{display:flex;align-items:center;gap:10px;font-size:15px;color:#fff}.chk input{width:20px;height:20px}"
            + ".hint{font-size:13px;color:#8b949e;margin:6px 0}"
            + "button{display:block;width:100%;margin-top:16px;padding:14px;border:0;border-radius:8px;background:#03a9f4;color:#fff;font-size:16px;font-weight:600}"
            + "button.sec{background:#2a2f36}.ok{background:#1b3a24;color:#7ee2a0;padding:12px;border-radius:8px}"
            + "</style></head><body>" + content + "</body></html>";
    }

    private static void respond(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden" : "Not Found";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
            + "Content-Type: " + type + "; charset=utf-8\r\n"
            + "Content-Length: " + b.length + "\r\n"
            + "Cache-Control: no-store\r\n"
            + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') buf.write(ch);
            if (buf.size() > 8192) break;
        }
        if (ch == -1 && buf.size() == 0) return null;
        return buf.toString("UTF-8");
    }

    private static Map<String, String> parseForm(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(i >= 0 ? pair.substring(0, i) : pair, "UTF-8");
                String v = i >= 0 ? URLDecoder.decode(pair.substring(i + 1), "UTF-8") : "";
                m.put(k, v);
            } catch (Exception ignored) { }
        }
        return m;
    }

    private static String get(Map<String, String> m, String k) {
        String v = m.get(k);
        return v == null ? "" : v;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("'", "&#39;").replace("\"", "&quot;");
    }

    // ---------- helpers for the TV screen ----------

    /** IPv4 address of the active network (Wi-Fi or Ethernet), or null. */
    static String localIp(Context ctx) {
        try {
            ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            if (n == null) return null;
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) return null;
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress a = la.getAddress();
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get IP: " + e);
        }
        return null;
    }

    static Bitmap qr(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (WriterException e) {
            return null;
        }
    }
}
