#!/usr/bin/env bash
# Creates the Capacitor Android project and copies Kept's native code into it.
# Runs the same way on your computer and in GitHub Actions (.github/workflows/android.yml).
# Needs Node 22+, JDK 21 and the Android SDK (API 36) (Android Studio installs it; GitHub's Ubuntu runners have it).
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
# crash reporting: the Firebase Android app's google-services.json, from the GOOGLE_SERVICES_JSON secret (base64) or a file next to this script
if [ -n "${GOOGLE_SERVICES_JSON:-}" ]; then echo "$GOOGLE_SERVICES_JSON" | base64 -d > android/app/google-services.json; fi
if [ -f google-services.json ]; then cp google-services.json android/app/google-services.json; fi
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
if "BootReceiver" not in s:
    s = s.replace("</manifest>", '    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n</manifest>', 1)
    boot = """        <receiver android:name=".BootReceiver" android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
"""
    s = s.replace("</application>", boot + "    </application>", 1)
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
# Google location services (fused provider); Crashlytics only when google-services.json is supplied
python3 - android/app/build.gradle android/build.gradle <<'PY'
import os, re, sys
app, root = sys.argv[1], sys.argv[2]
s = open(app).read()
if "play-services-location" not in s:
    i = s.rindex("dependencies {")
    s = s[:i] + 'dependencies {\n    implementation "com.google.android.gms:play-services-location:21.3.0"\n' + s[i + len("dependencies {"):]
has_gs = os.path.exists("android/app/google-services.json")
if has_gs and "firebase-crashlytics" not in s:
    i = s.rindex("dependencies {")
    s = s[:i] + 'dependencies {\n    implementation platform("com.google.firebase:firebase-bom:33.7.0")\n    implementation "com.google.firebase:firebase-crashlytics"\n' + s[i + len("dependencies {"):]
    s += "\napply plugin: 'com.google.gms.google-services'\napply plugin: 'com.google.firebase.crashlytics'\n"
    r = open(root).read()
    if "firebase-crashlytics-gradle" not in r:
        r = re.sub(r"(classpath ['\"]com\.android\.tools\.build:gradle:[^'\"]+['\"])", r"\1\n        classpath 'com.google.gms:google-services:4.4.2'\n        classpath 'com.google.firebase:firebase-crashlytics-gradle:3.0.2'", r, count=1)
        if "google-services:4.4.2" not in r and "com.google.gms:google-services" not in r: raise SystemExit("could not add the Firebase Gradle plugins")
        open(root, "w").write(r)
open(app, "w").write(s)
print("Crash reporting: " + ("Firebase Crashlytics" if has_gs else "off (no google-services.json)"))
PY
# version for the Play Store
GR=android/app/build.gradle
sed -i -E "s/versionCode [0-9]+/versionCode ${VERSION_CODE:-1}/; s/versionName \"[^\"]*\"/versionName \"${VERSION_NAME:-6.0}\"/" "$GR"
npx cap sync android
echo "Done. Build: cd android && ./gradlew assembleDebug   (or: npx cap open android, then Run)"
