# HomeCanvas for Google TV

Shows a Home Assistant dashboard as the screensaver on the **Google TV Streamer 4K** and on other Google TV or Android TV devices, including the Chromecast with Google TV.

It's a native Android screensaver (`DreamService`) with a full-screen WebView, so it takes over from Google TV's Ambient mode when the TV is idle. Press any button on the remote to go back to the TV.

## Features

- Loads any HA URL (dashboard, view, or kiosk-mode page).
- **Log in once:** log in from the app's "Open HA / log in" button and the screensaver uses the same login. You can also paste a **long-lived access token** so the screensaver never shows the login page.
- Retries every 30 s if HA can't be reached, for example while HA is rebooting.
- Optional reload every N minutes.
- **Layout width override:** the Streamer renders at about 960 CSS px wide, which gives HA a mobile-style layout. Set this to `1280` or `1920` for a desktop-style layout.
- Optional interactive mode: the remote controls the dashboard and Back exits.
- Works with `http://` and self-signed `https://`.
- **Phone setup:** scan the QR code on the TV and fill in the URL and token on your phone, so there's no typing with the remote.
- Can also be configured from your PC over adb.

## Build

GitHub Actions builds the APK on every push. Download it from the run's **Artifacts**. Pushing a `v*` tag also attaches the APK to a release.

To build locally, use Android Studio or `gradle assembleRelease` with the Android SDK installed.

> **Signing:** without secrets, each CI build is signed with a fresh debug key, so you'll need to uninstall before installing a newer build. To keep one key across builds, add the repo secrets `KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`:
> ```
> keytool -genkeypair -v -keystore release.jks -alias ha -keyalg RSA -keysize 2048 -validity 10000
> base64 -w0 release.jks   # -> KEYSTORE_BASE64
> ```

## Install on the Google TV Streamer

1. On the TV, go to **Settings → System → About** and click **Android TV OS build** 7 times to turn on Developer options.
2. Go to **Settings → System → Developer options** and turn on **USB debugging**. Also turn on **Wireless debugging** if your build shows it.
3. From your PC:
   ```bash
   adb connect <tv-ip>:5555          # accept the prompt on the TV
   adb install -r homecanvas.apk
   ```

## Configure

### From your phone (easiest)

1. Open **HomeCanvas** from the apps row on the TV.
2. Scan the QR code on the right of the screen with your phone's camera. Your phone must be on the same network.
3. Paste your dashboard URL and token, then tap **Save** or **Save & preview on TV**.

The setup page only works while the HomeCanvas settings screen is open on the TV. The link includes a random key that changes each time you open the app, so nobody else on your network can change the settings.

### On the TV

Enter the settings with the remote and press **Save**.

### From your PC (adb)

```bash
adb shell am start -n com.mimikm.homecanvas/.SettingsActivity \
  --es url "http://192.168.1.10:8123/dashboard-tv/0?kiosk" \
  --es token "eyJhbGciOi...your-long-lived-token" \
  --ei reload 60 --ei width 1280 --ez bright true
```

Authentication options, from easiest to most robust:

| Option | How |
|---|---|
| Log in once | Click **Open HA / log in** in the app, sign in, and tick "Keep me logged in". |
| Long-lived token | HA → Profile → Security → Long-lived access tokens. Paste it on the phone setup page or with the adb command above. |
| Trusted networks | Add the Streamer's IP to HA's `trusted_networks` auth provider with `allow_bypass_login: true`. |

Tip: make a dedicated dashboard for the TV, and use [kiosk-mode](https://github.com/NemesisRE/kiosk-mode) (`?kiosk`) to hide the header and sidebar.

## Set it as the screensaver

Google TV only offers its own Ambient mode in Settings, so set the screensaver with adb:

```bash
adb shell settings put secure screensaver_components com.mimikm.homecanvas/.HaDreamService
adb shell settings put secure screensaver_enabled 1
```

Test it straight away:

```bash
adb shell am start -n com.android.systemui/.Somnambulator
```

Set how long the TV waits before starting the screensaver in **Settings → System → Ambient mode / Screen saver → When to start**. If you want the dashboard to stay on, also set **Settings → System → Power & energy → Turn off display** to a long time or Never.

To go back to Google's Ambient mode:

```bash
adb shell settings delete secure screensaver_components
```

> A Google TV system update can occasionally reset `screensaver_components`. If Ambient mode comes back, run the command again.

## Troubleshooting

- **Blank or login page in the screensaver:** use "Open HA / log in" once, or set a token. "Clear login data" resets everything.
- **Layout too small or too big:** change the layout width (`0` = device default, `1280`, `1920`).
- **Certificate error:** tick "Ignore HTTPS certificate errors", or use the local `http://` URL.
- **Logs:** `adb logcat -s HomeCanvas chromium`
