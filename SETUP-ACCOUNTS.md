# Kept v6: accounts (Firebase) — setup, move-over and release

Kept v6 replaces the PIN with real accounts. Each driver signs in with email and password (or Google, in a browser) and sees only their own data. Data lives in Google Firebase: Authentication for sign-in, Cloud Firestore for records. The Apps Script backend stays for the shared services (fuel prices, VIN, MPG lookup, recalls, AI brief) and for moving data out of the old Sheet.

Nothing here touches the live site until step 6.

## 1. Create the Firebase project (about 10 minutes)
1. Go to https://console.firebase.google.com → **Create a project** → name it `kept` → Google Analytics off (not needed).
2. **Project settings** (gear) → **Your apps** → the web icon `</>` → nickname `Kept web` → **Register app**. Copy the `firebaseConfig` values shown (`apiKey`, `authDomain`, `projectId`, `appId`). These values are public by design; the security rules in step 3 protect the data.
3. **Build → Authentication → Get started → Sign-in method**:
   - Enable **Email/Password**.
   - Enable **Google**: pick a support email and save. Google sign-in appears in browsers only; the Android app uses email and password.
4. **Authentication → Settings → Authorized domains → Add domain** → `kept.usebuildflow.io`.

## 2. Create the database
1. **Build → Firestore Database → Create database** → choose a US location (for example `nam5`, multi-region US) → **Start in production mode**.
2. Open the **Rules** tab, replace everything with the contents of `firebase/firestore.rules`, and click **Publish**. These rules let each driver read and write only `users/<their id>/…`, and close everything else.

## 3. Optional: receipt photos in the cloud
Without this step, receipt photos stay on the phone that took them. Cloud Storage now needs the pay-as-you-go **Blaze** plan; with one driver the cost is usually cents a month, but set a budget alert.
1. Upgrade to Blaze, then **Build → Storage → Get started**.
2. **Rules** tab → paste `firebase/storage.rules` → **Publish**.
3. Copy the bucket name (for example `kept-xxxx.firebasestorage.app`) as `storageBucket` in config.js.

**Backups.** On the Blaze plan, open **Firestore Database → Disaster recovery** and turn on daily scheduled backups. On the free plan, use Settings → **Download backup** in Kept now and then.

## 4. Connect the services backend
1. Open the Apps Script project → paste the new `Code.gs` → **Deploy → Manage deployments → Edit → Version: New version → Deploy** (the URL stays the same).
2. **Project Settings → Script properties → Add**: `FIREBASE_API_KEY` = the `apiKey` from step 1. Signed-in drivers can then use fuel prices, VIN, MPG lookup, recalls and the AI brief.
3. Run `testIntegrations` once from the editor and check the log (VIN, fuel, MPG). Run `installNightlyBackup` once to copy the old Sheet to Drive every night while it is still in use.

## 5. Production config.js (on the live site, replaces the current one)
```js
window.KEPT_CONFIG = {
  firebase: {
    apiKey: "…", authDomain: "kept-xxxx.firebaseapp.com", projectId: "kept-xxxx", appId: "1:…:web:…"
    // , storageBucket: "kept-xxxx.firebasestorage.app"   // only after step 3
  },
  servicesUrl: "https://<your worker or Apps Script URL>",   // the same URL apiUrl has today
  apiUrl: "https://<same URL>",                              // keep until everyone has moved their data over
  privacyUrl: "https://kept.usebuildflow.io/privacy.html"     // once the privacy policy is published
};
```
No `staging` line. On kept.usebuildflow.io the app now refuses to start with a staging or empty config and shows "Kept isn't set up correctly", so the Nov 11 mix-up can't silently happen again.

## 6. Release
1. Upload `index.html`, `sw.js` and the new `config.js` to the live site. Update `worker.js` in Cloudflare (it now only accepts calls from the Kept site and the Android app).
2. Open the site: you should see the sign-in screen.
3. **Move the PIN data over:** create the account → onboarding offers **Bring over data from the PIN version** → enter the old PIN. Kept copies trips, income, expenses, vehicles and settings into the account; nothing is deleted from the Sheet. Check the totals on My money against the old app.
4. When every driver has moved over: Script properties → `PIN_ACCESS` = `off`. The Sheet can then no longer be opened with a PIN.

**Rollback:** re-upload the previous `index.html`, `sw.js` and `config.js` (v5.0 in `kept-v5-production.zip`). Account data stays in Firebase and the Sheet is unchanged, so nothing is lost either way.

## 7. Android app
See `android/README-ANDROID.md`. In short: push the repo to GitHub, then Actions → **Build Kept Android** → Run workflow → download `app-debug.apk`.

## What was tested, and what wasn't
- **Tested, with a real Firebase Authentication emulator:** sign-up, sign-in, wrong password, weak password, password reset, staying signed in, sign-out clearing the phone, two drivers kept apart, PIN data import, account deletion with password check. Automated accessibility scan: 0 violations on the sign-in, account, import and delete screens.
- **Tested with a stand-in database:** writes go only to the driver's own space, settings merge field by field, backup and restore never double income.
- **Not tested here:** the real Firestore service and the security rules. The Firestore emulator needs a download this workspace can't reach. After step 2, run one test with two accounts on two phones to confirm each sees only its own data.
