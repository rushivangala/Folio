<p align="center"><img src="docs/icon.svg" width="96" alt=""></p>

# Folio

Scan paper into searchable, named PDFs that know what they are.

Part of a small suite of offline Android apps for Android 13 and newer (built for a Pixel 10 Pro on Android 17).
Nothing leaves the phone: the app has no internet permission.

## Features

Folio is being built in steps; this list grows as each one lands (see CHANGELOG).

* Library with collections (Receipts, Bills, Letters, ID, Manuals, Notes) and the suite's settings and themes.

Planned for 1.0.0:

* Live camera with page-edge outline and auto-capture; multi-page scans; crop, perspective flattening and filters.
* Import from Photos, Files and the share sheet ("Scan with Folio").
* PDFs with an invisible text layer, so any viewer can search and copy; saved to Documents/Folio by default.
* On-device text recognition in English and Swedish; document type, automatic names, bill and receipt reading.
* "Pay by" dates become Daybook tasks; search that understands amounts and dates; duplicate warnings.
* Share with personal numbers, card numbers and signatures redacted; a locked collection for IDs and contracts.
* Quick Settings tile **Scan**; widgets **Scan** and **Recent scans**.

## Build

Every push to `main` builds a signed APK with GitHub Actions (**Actions → Build APK → latest run → Artifacts**).
Pushing a tag such as `v1.0.0`, or a commit to `main` whose message contains `[release]`, also publishes the APK on the **Releases** page.

Locally: `./gradlew assembleRelease` (JDK 17 and the Android SDK, API 36). The APK lands in `app/build/outputs/apk/release/`.

`versionCode` is the number of commits, so every build installs over the previous one. `versionName` lives in `gradle.properties`.

The release build fails if the merged manifest asks for `android.permission.INTERNET` (`checkReleaseNoInternet` in `app/build.gradle.kts`), so a library can't add it unnoticed.

To check the interface without a phone: `python3 tools/screenshots.py` renders every screen in light and dark at Pixel 10 Pro size with a mocked `window.Native` (needs Python Playwright).

## Signing

Android only installs an update if it is signed with the same key as the installed app.

* **With your own key (recommended).** Add four repository secrets under **Settings → Secrets and variables → Actions**:
  `SIGNING_KEYSTORE_BASE64` (the keystore file, base64-encoded), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
  Use the same key in all seven repositories of the suite.
* **Without secrets** the build falls back to `ci/ci-signing.jks`, a shared key that is public in this repository.
  Fine for trying things out; anyone could sign an "update" with it, so switch to your own key for daily use.
  Changing keys later means uninstalling once (export your data first).

## Part of a suite

Calculator, Clock, Daybook, Files, Photos, Folio and Margin share one design kit (`app/src/main/assets/www/kit`, `app/src/main/java/io/github/rushivangala/kit`, `app/src/main/res/*kit*`):
the same themes (Gruvbox, Nord, Everforest, Tokyo Night, Catppuccin, Solarized, AMOLED, Paper and light variants), light/dark following the phone,
optional wallpaper colours, the same fonts (Figtree and JetBrains Mono), predictive back, edge-to-edge layout and home-screen widget style.
Keep those files identical across the repositories.

None of the apps requests the internet permission.
