# Build & install

## Requirements

- **Android Studio** Ladybug (2024.2) or newer.
- **Gradle JDK 17 or 21.** Android Studio → *Settings → Build, Execution, Deployment → Build Tools → Gradle →
  Gradle JDK* → choose *jbr-17*/*jbr-21* (the bundled JetBrains Runtime works). Newer JDKs (23+) are not supported
  by Gradle 8.10 / AGP 8.7 and fail with "Unsupported class file major version".
- Android SDK Platform **34** (Studio installs it on first sync).
- A phone with **Android 8.0 (API 26)** or newer. Focus mode, reminders and the foreground timer are best tested on a
  real device (emulators work for everything else).
- Firebase set up as in [SETUP_FIREBASE.md](SETUP_FIREBASE.md) and `app/google-services.json` in place.

## Android Studio

1. `git clone https://github.com/iitnitget-beep/Detox.git` (or *File → New → Project from Version Control*).
2. Copy your `google-services.json` to `Detox/app/google-services.json`.
3. *File → Open* → select the `Detox` folder → wait for **Gradle sync** (first sync downloads ~1 GB).
4. Enable *Developer options → USB debugging* on the phone and connect it (or pair over Wi-Fi).
5. Select the **app** run configuration and your device → **Run ▶**.

On first launch you get the onboarding (one explainer per permission). Tap **Allow** on the ones you want — nothing is
requested before that.

## Command line

```bash
./gradlew testDebugUnitTest          # unit tests (timer, plan parser, reminders, calendar, merge, stats)
./gradlew assembleDebug              # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease            # minified (R8); signed with the debug key for personal use
adb install -r app/build/outputs/apk/release/app-release.apk
```

Windows: use `gradlew.bat`.

## Release signing (only if you distribute the app)

1. *Build → Generate Signed App Bundle / APK* → create a keystore (keep it and its passwords safe, never commit it —
   `*.jks`/`*.keystore` are git-ignored).
2. Replace `signingConfig = signingConfigs.getByName("debug")` in `app/build.gradle.kts` with your own signing config,
   e.g. read from a git-ignored `keystore.properties`.
3. Register the release key's **SHA-1** (and Play App Signing's SHA-1 if you use Google Play) in Firebase, download the
   new `google-services.json` — otherwise Google sign-in / Calendar fail in release builds.

## Device settings that matter

- **Battery**: allow *Unrestricted* battery for Lecture Tracker (asked in onboarding). On Xiaomi/Redmi/POCO also
  enable *Autostart*, on Samsung put the app in *Never sleeping apps*, on Oppo/Vivo/Realme allow *Auto launch* and
  *Run in background*. See <https://dontkillmyapp.com> for your brand.
- **Xiaomi (MIUI/HyperOS)**: also allow *Display pop-up windows while running in the background* (needed for the
  Focus overlay and "Back to study").
- **Notifications**: allowed in onboarding; reminders use the *Lecture reminders* channel, the timer uses *Study
  session* (silent).

## Troubleshooting

| Symptom | Fix |
|---|---|
| Settings → *Last sync problem*: "Anonymous sign-in is disabled" | Enable Anonymous in Firebase Authentication. |
| "Firestore denied access" / `PERMISSION_DENIED` | Deploy the rules: `firebase deploy --only firestore:rules`. |
| "Firestore database not found" | Create the Firestore database (step 3 of the setup). |
| Plan shows locally but "Cloud copy will be created…" stays | Device is offline or not signed in; open Settings → *Sync now*. Without deployed functions the phone uploads the plan itself. |
| Google sign-in: "Developer console is not set up correctly", `[28444]`, `DEVELOPER_ERROR` | SHA-1 of your build key not registered (setup step 6), or an old `google-services.json`. |
| Calendar: "Access blocked: app has not completed verification" | Add your Google account as a test user on the OAuth consent screen (setup step 7). |
| Calendar: "Google needs your permission again" | Settings → Google Calendar → **Reconnect** (access was revoked or expired). |
| Focus overlay never appears | Settings → Permissions: *Usage access* and *Display over other apps* must be allowed; Focus mode on; the session must be running and before the planned end. |
| Reminders late or missing | Allow unrestricted battery; check brand-specific autostart settings; the server fallback needs the functions deployed. |
| Build: "Unsupported class file major version 6x" | Set Gradle JDK to 17 or 21 (see Requirements). |
| Build: "File google-services.json is missing" | Copy it to `app/google-services.json`. |
