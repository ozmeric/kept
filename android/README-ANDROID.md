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

### Google Play release (later)
Google Play needs a signed bundle (`.aab`). Create an upload key once on any computer with Java:

```bash
keytool -genkeypair -v -keystore kept-upload.keystore -alias kept -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 kept-upload.keystore > keystore.txt   # on a Mac: base64 -i kept-upload.keystore -o keystore.txt
```

Then in GitHub → Settings → Secrets and variables → Actions add `KEYSTORE_BASE64` (contents of keystore.txt), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`kept`) and `KEY_PASSWORD`. The next run also produces `app-release.aab`. Keep the keystore file safe: Play only accepts updates signed with the same key.

## Build it on your computer (alternative)
You need Node.js 18+ and Android Studio (it includes the Android SDK and JDK 17).

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
- Tested: the web app's handling of native trips, the widget data and Share to Kept, with a simulated Android bridge; all Java compiles against the Android and Capacitor 6.1.2 method signatures.
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
