# Kept on Google Play: submission pack

Draft text and answers for Play Console. Nothing here has been submitted. Items marked **[you]** need a decision or detail from the owner.

## 1. Before the first upload
| Item | Status |
|---|---|
| Organization developer account (needs a D-U-N-S number, organization website and phone) | **[you]** |
| 4 signing secrets in GitHub (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) | Added Nov 15, 2026 |
| Signed bundle `app-release.aab` | Built by Actions → Build Kept Android once the secrets exist (artifact `kept-android`) |
| Target API 36, minimum Android 7.0 | Done (checked in the build) |
| Version code | Automatic: the workflow run number |
| Privacy policy at `https://kept.usebuildflow.io/privacy.html` | Draft in repo. **[you]** company name, address, support email, then remove the DRAFT banner and set `privacyUrl` in `config.js` |
| Terms of service page | Not written. **[you]** needed before the one-line consent on sign-up appears (`termsUrl` in `config.js`) |
| Account deletion web page (Play requires a link that works without the app) | Not written; needs the support email. In-app deletion exists: Settings → Account → Delete my account |
| In-app location disclosure before the permission prompt | Done (shown once after setup in the Android app; tracking does not start until Continue) |
| Test on a real phone (screen-off miles, restart, ride detection) | Not done |

Play App Signing: on first upload, choose "Use Google-generated key" and upload the `.aab`; the key in GitHub becomes the upload key. Keep `kept-upload.keystore` and its password safe.

## 2. Store listing (English, US)
- **App name (30 max):** Kept: Gig Miles & Take-Home
- **Short description (80 max):** Track miles, earnings and car costs. See what you really keep from each shift.
- **Category:** Finance (alternative: Auto & Vehicles) **[you]**
- **Contact email / website:** **[you]**

**Full description**

Kept shows gig drivers what they actually keep.

Start driving and Kept counts your miles, even with the screen off. Add what you earned, and Kept takes off fuel, car costs and an estimated tax reserve to show today's take-home.

What Kept does:
- Counts work and personal miles in the background, with an ongoing notice while it runs
- Can start and finish rides from your gig apps' notifications (optional; you choose the apps)
- Tracks earnings per app: Uber, Lyft, DoorDash, Instacart, Grubhub, Amazon Flex and others
- Logs expenses and reads receipts on your phone
- Estimates car costs per mile, including depreciation
- Keeps a mileage log you can export for your tax preparer
- Estimates a tax reserve; every estimate is labelled as an estimate
- Checks an offer before you take it: pay, miles and time against your own costs
- Works in English, Turkish and Dutch

Kept does not accept or decline orders for you, does not sign in to your gig accounts, shows no ads and does not sell your data. Tax figures are estimates for planning, not tax advice.

**Graphics needed:** icon 512×512 (have `icon-512.png`), feature graphic 1024×500 (to make), at least 2 phone screenshots (to capture from the real app after the phone test).

## 3. App content answers
- **Ads:** No
- **App access:** Sign-in required. Give reviewers a test account (email + password) **[you]** create one in the live app
- **Content rating:** questionnaire; no violence, sexual content, gambling or user-to-user chat. Expected rating: Everyone
- **Target audience:** 18 and over
- **News app / health / government:** No
- **Financial features:** personal budgeting/expense tracking only; no loans, payments, banking or investment

## 4. Data safety (confirm each line on the day of submission)
| Data | Collected | Purpose | Optional? |
|---|---|---|---|
| Email address | Yes | Account | Required |
| Precise location | Yes, including in the background | App functionality (mileage log, ride start/end) | Tracking can be paused; app works with manual miles |
| Financial info the driver types (earnings, expenses) | Yes | App functionality | Required for the main feature |
| Photos (receipts) | Stay on the phone unless cloud photos are enabled (not enabled today) | App functionality | Optional |
| Crash logs / diagnostics | Only if Crashlytics is switched on (`google-services.json`) | Analytics, stability | — |

- Data is encrypted in transit: Yes
- Users can request deletion: Yes (in app; web link still to add)
- Data sold: No. Shared with third parties: No (Google Firebase, the Apps Script backend and Gemini act as service providers; the AI brief sends summary numbers only)

## 5. Sensitive permissions
**Background location (`ACCESS_BACKGROUND_LOCATION`) and foreground service type location**

Core feature: automatic mileage tracking for a gig driver's tax mileage log.

Declaration text:
> Kept is a mileage log for gig drivers. Drivers work with a delivery or rideshare app in the foreground and the phone screen off, so Kept must record location in the background to count the miles of each work drive. Without background location the mileage log would miss most work miles. Tracking shows an ongoing notification, can be paused at any time, and an in-app disclosure is shown before the system permission prompt.

Video (30–60 s, required): open Kept → the location disclosure sheet → Continue → the system prompt → choose "Allow all the time" → press Start → show the ongoing notification → switch to another app → return and show miles counted.

**Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`)**

Optional. Reads notifications only from gig apps the driver selects, to see when an order starts or finishes. The text stays on the phone (Settings → Notification log) and is not uploaded. If review objects, the app works fully without it (manual Start ride / Complete ride).

**Other:** `POST_NOTIFICATIONS` (tracking notice, ride alerts), `RECEIVE_BOOT_COMPLETED` (resume tracking after restart), `FOREGROUND_SERVICE_LOCATION`.

## 6. Release order
1. Internal testing track: upload the first `.aab`, add your own Google account as tester, install from Play, run the phone test.
2. Fix what the phone test finds.
3. Complete the store listing, app content, data safety and permission declarations.
4. Production (organization accounts can go straight to production review; personal accounts need a 12-tester, 14-day closed test first).
5. After approval, change `android.html` to link to the Play listing.

Review time is set by Google and can't be promised; background location apps are often reviewed more slowly.
