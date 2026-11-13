#!/usr/bin/env bash
# Creates the Capacitor Android project and copies Kept's native code into it.
# Runs the same way on your computer and in GitHub Actions (.github/workflows/android.yml).
# Needs Node 18+, JDK 17 and the Android SDK (Android Studio installs it; GitHub's Ubuntu runners have it).
# Optional environment variables:
#   KEPT_URL       site the app opens (default https://kept.usebuildflow.io)
#   VERSION_CODE   whole number that must grow with every Play Store upload (default 1)
#   VERSION_NAME   version shown to users (default 6.0)
set -e
cd "$(dirname "$0")"
npm install --no-audit --no-fund
node -e '
const fs = require("fs"), f = "capacitor.config.json", c = JSON.parse(fs.readFileSync(f, "utf8"));
if (process.env.KEPT_URL) c.server.url = process.env.KEPT_URL;
fs.writeFileSync(f, JSON.stringify(c, null, 2) + "\n");
console.log("App opens " + c.server.url);'
[ -d android ] || npx cap add android
SRC=native/src/main
DST=android/app/src/main
PKG=java/io/usebuildflow/kept
mkdir -p "$DST/$PKG" "$DST/res/drawable" "$DST/res/layout" "$DST/res/xml"
cp "$SRC/$PKG/"*.java "$DST/$PKG/"
cp "$SRC/res/drawable/"*.xml "$DST/res/drawable/"
cp "$SRC/res/layout/"*.xml "$DST/res/layout/"
cp "$SRC/res/xml/"*.xml "$DST/res/xml/"
# merge manifest entries once (each block checks its own marker, so re-running is safe)
python3 - "$DST/AndroidManifest.xml" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
if "GigNotificationListener" not in s:
    perms = """    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
"""
    comps = """        <service android:name=".TrackingService" android:exported="false" android:foregroundServiceType="location" />
        <receiver android:name=".ActionReceiver" android:exported="false" />
        <service android:name=".GigNotificationListener" android:exported="true" android:label="Kept ride detection"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter><action android:name="android.service.notification.NotificationListenerService" /></intent-filter>
        </service>
"""
    s = s.replace("</manifest>", perms + "</manifest>", 1)
    s = s.replace("</application>", comps + "    </application>", 1)
if "KeptWidget" not in s:
    widget = """        <receiver android:name=".KeptWidget" android:exported="false" android:label="Kept">
            <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE" /></intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/kept_widget_info" />
        </receiver>
"""
    s = s.replace("</application>", widget + "    </application>", 1)
if "android.intent.action.SEND" not in s:
    share = """            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="image/*" />
            </intent-filter>
"""
    i = s.find('android.intent.category.LAUNCHER')
    j = s.find("</intent-filter>", i) + len("</intent-filter>\n")
    if i < 0 or j < len("</intent-filter>\n"): raise SystemExit("MainActivity launcher intent-filter not found")
    s = s[:j] + share + s[j:]
open(p, "w").write(s)
PY
# version for the Play Store
GR=android/app/build.gradle
sed -i -E "s/versionCode [0-9]+/versionCode ${VERSION_CODE:-1}/; s/versionName \"[^\"]*\"/versionName \"${VERSION_NAME:-6.0}\"/" "$GR"
npx cap sync android
echo "Done. Build: cd android && ./gradlew assembleDebug   (or: npx cap open android, then Run)"
