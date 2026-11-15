# Kept for Android

The Android app is a thin shell around the web app at `https://kept.usebuildflow.io`. When a new `index.html` goes live, the phone app updates by itself; you only rebuild the app when something in this `android/` folder changes.

The shell adds what a browser can't do:

1. **Tracks every mile with the screen off.** A foreground service records GPS and shows an ongoing "Kept · Personal · 3.2 mi" notification.
2. **Detects rides from gig-app notifications.**
   - When DoorDash, Uber, Lyft, Instacart, Grubhub or Amazon Flex shows its "you're online" notification, Kept switches to that app.
   - When an order is accepted, Kept starts a ride and shows "Ride started · DoorDash", with **Not a ride** and **Complete** buttons.
   - When the gig app says the order was delivered, the ride completes and Kept asks what it paid.
3. **Notification buttons** for Start ride, Complete ride and Go personal.
4. **Home-screen widget:** today's take-home, what Kept is tracking, and one button (Start ride, Complete ride or Open Kept).
5. **Share to Kept:** in a gig app, take a screenshot of an offer, tap Share → Kept. Kept opens Check an offer and reads the numbers; you confirm them. Kept never accepts or declines an order.
6. **Where each drive starts and ends** is saved for the mileage log (switch it off on the Profit Map screen).

## Build it in the cloud (recommended, no Android Studio)

The workflow `.github/workflows/android.yml` builds the app on GitHub.

1. Push this repo to GitHub (the `android/` folder and `.github/workflows/android.yml` must be included).
2. On GitHub open **Actions → Build Kept Android → Run workflow**. Leave the site address as it is unless the app should open a different site.
3. When the run finishes (about 8–10 minutes), scroll to **Artifacts** and download **kept-android**. Inside is `app-debug.apk`.
4. Copy the APK to the phone and open it. Android asks to allow installs from that source; allow it once.

The workflow also runs by itself whenever something in `android/` changes.

### Signing
Every phone only accepts an update signed with the same key as the installed app. Add the four secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` in GitHub → Settings → Secrets and variables → Actions. Builds then produce a signed `app-release.apk` (published as `kept.apk` from `main`) and a signed `app-release.aab` for Google Play. Keep the keystore file and its password somewhere safe: without them, updates can't be published.

Without the secrets, the build makes a debug APK with a new random key each time; it cannot install over an earlier build.

## Build it on your computer (alternative)
You need Node.js 22+ and a current Android Studio (Android SDK 36, JDK 21).

```bash
cd android
bash setup-android.sh        # npm install, creates the Android project, copies Kept's native code, merges the manifest
npx cap open android         # opens Android Studio; wait for Gradle sync, then press ▶ Run with the phone plugged in
```

## First run on the phone
1. Open Kept and **sign in to your Kept account** (or create one). Finish onboarding. When asked for **Location**, choose *While using the app*.
2. Settings → Your apps → **Allow notification access** → switch **Kept ride detection** on.
3. Recommended: Android settings → Apps → Kept → Location → **Allow all the time**, so rides start even when Kept is closed.
4. Samsung, Xiaomi and some other phones stop background apps. Apps → Kept → Battery → **Unrestricted**.
5. Optional: long-press the home screen → Widgets → Kept.

## Updating safely
Trips recorded in the background stay on the phone until the account confirms them. Before installing a new version, signing out or changing phones, open Kept → Settings → **Safe to update?** and wait for the green "Everything is saved in your account". Install new versions over the old one; don't uninstall first.

## What Stage 1 added (Nov 2026)
- **Android 16 target** (Capacitor 8.5.3, API 36; minimum Android 7.0), as Google Play requires for new apps.
- **Google fused location** (GPS + Wi-Fi + sensors) when Play services is on the phone; plain GPS otherwise. State is written to storage every 15 seconds instead of on every fix.
- **"Kept stopped counting miles" alert** when Android stops tracking, and automatic restart when Kept is opened.
- **After a phone restart or an app update**, tracking resumes by itself if location is set to "Allow all the time"; otherwise Kept shows a notice to tap (an Android rule).
- **Crash reporting** (Firebase Crashlytics) switches on when `google-services.json` is supplied: add the Firebase Android app (package `io.usebuildflow.kept`), download the file, and store it base64-encoded as the GitHub secret `GOOGLE_SERVICES_JSON`.

## Tuning ride detection (learn mode)
Gig apps word their notifications differently and change them over time. The built-in keywords are a starting point:
- Starts a ride: *accepted, new order, head to pickup, pick up…*
- Completes a ride: *delivered, delivery complete, trip complete, you earned…*

To tune them:
1. Drive a few orders with notification access on.
2. Open **Settings → Notification log**. It shows every notification from your gig apps and what Kept did with it (`→ start`, `→ complete`, `→ online`).
3. Send the exact wording to the developer. Rules can be adjusted without rebuilding the app, through `settings.notifRules`:

```json
{ "enabled": true,
  "apps": { "com.doordash.driverapp": { "apps": ["DoorDash"], "start": ["accepted"], "done": ["delivered"] } } }
```

| App | Package | Status |
|---|---|---|
| DoorDash Dasher | `com.doordash.driverapp` | verified |
| Uber Driver (also used for Uber Eats) | `com.ubercab.driver` | verified |
| Lyft Driver | `com.lyft.android.driver` | check in the log |
| Instacart Shopper | `com.instacart.shopper` | check in the log |
| Grubhub Driver | `com.grubhub.driver` | check in the log |
| Amazon Flex | `com.amazon.flex.rabbit` | check in the log |

Uber and Uber Eats share one app. Kept picks Uber Eats when the notification mentions eats, deliver or order.

## What has and hasn't been tested
- Tested: the web app's handling of native trips (released from the phone only after the account confirms them), the Safe to update check, the widget data and Share to Kept, with a simulated Android bridge; the app builds on GitHub Actions.
- Not yet tested: a real build and a real phone. The first GitHub run is the first full build; then drive 5 shifts per app and compare the miles with the odometer (Vehicles → Set dates → Odometer now does the comparison for you).

## Limits
- **iPhone:** iOS doesn't let apps read other apps' notifications, so automatic ride detection is Android-only. An iPhone app could do background miles with manual Start/Complete; it needs a Mac with Xcode and an Apple Developer account ($99/year).
- **Google Play:** the store reviews notification access and background location. Fill in the "Permissions declaration" form, explain mileage tracking for taxes, and record a short video. Plan about one week for review.
- **Google sign-in** is shown in browsers only; in the Android app, sign in with email and password.
- **Navigation:** the Navigate button hands off to Google Maps or Waze.

## Files
| File | What it does |
|---|---|
| `native/.../TrackingService.java` | Always-on GPS, splits miles into Personal, Online and Order trips, ongoing notification |
| `native/.../GigNotificationListener.java` | Reads gig-app notifications, switches app, starts and completes rides, learn log |
| `native/.../ActionReceiver.java` | Notification and widget buttons |
| `native/.../KeptWidget.java` + `res/layout/kept_widget.xml` | Home-screen widget |
| `native/.../MainActivity.java` | Registers the plugin; receives screenshots shared to Kept |
| `native/.../KeptNativePlugin.java` | JavaScript bridge (`window.Capacitor.Plugins.KeptNative`) used by `index.html` |
| `native/.../TripStore.java` | On-device storage. Trips stay on the phone until the web app confirms it saved them (`ackTrips`) |
| `setup-android.sh` | Creates the Capacitor project and copies the native code (used by GitHub Actions and on your computer) |
| `capacitor.config.json` | App id `io.usebuildflow.kept`; loads kept.usebuildflow.io |
