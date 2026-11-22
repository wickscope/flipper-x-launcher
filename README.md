# FLIPPER X Launcher

A minimal Android **home-screen launcher** for the Summit Flip. It's a single full-screen
WebView that loads the bundled FLIPPER X interface from the app's own assets — so once it's
set as the default Home app, FLIPPER X **is** the phone's entire UI. Fully offline, no Google,
no `INTERNET` permission.

## What it does
- Registers as `HOME` + `LAUNCHER` → becomes the phone's home screen.
- Loads `assets/flipperx/index.html` in an immersive full-screen WebView (JS + localStorage on).
- Swallows the Back key so it never drops out of the home screen; forwards keypad keys to the app.
- `minSdk 26`, `targetSdk 33`, 32-bit friendly (no native code) → runs on the MT6739 / Android 12.

## Build the APK (GitHub Actions — no local Android SDK needed)
Pushing to `main` runs `.github/workflows/build.yml`, which:
1. installs JDK 17 + the Android SDK + Gradle 8.7 on the runner,
2. runs `gradle assembleDebug`,
3. signs the APK with the private key stored in the repo secrets `FX_KEYSTORE_B64` and
   `FX_KEYSTORE_PASSWORD` (the key itself is never committed),
4. publishes `app-debug.apk` as a GitHub Release (`build-N`).

Download `app-debug.apk` from the latest Release.

To build in your own fork, generate a keystore (`keytool -genkeypair -keystore fx.jks -alias fxkey ...`)
and add those two secrets (`base64 -w0 fx.jks` for the first).

### Or build locally (if you install Android Studio)
Open this folder in Android Studio and **Build → Build APK**, or from a terminal with the
SDK installed: `gradle assembleDebug`. Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Install on the phone
1. Copy `app-debug.apk` to the phone's SD card (over USB / MTP).
2. On the phone: **Settings → Security / Apps → allow "Install unknown apps"** for the file manager.
3. Open the APK with the file manager → **Install**.
4. Press the Home key → choose **FLIPPER X** → **Always**. It's now the whole interface.
   (To get back to the stock launcher later: Settings → Apps → Default apps → Home app.)

## Update the interface
Replace `app/src/main/assets/flipperx/index.html` with a new FLIPPER X build and rebuild.
The music lives on the SD card's `Music` folder and is read by the FLIPPER X Music module.
