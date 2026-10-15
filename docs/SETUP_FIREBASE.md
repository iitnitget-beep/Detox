# Firebase setup (project `deeproot-e4d17`)

Everything here is done once. The Android app is `com.deeproot.app`; all Cloud Functions run in **`asia-south1`
(Mumbai)** — if you change the region, change it in both `gradle.properties` (`functionsRegion`) and
`functions/src/config.ts` (`REGION`).

> **No secrets live in this repository.** `app/google-services.json` is git-ignored (the repo is public). The OAuth
> client ids are read from that file at build time (`R.string.default_web_client_id`), Calendar access tokens are
> requested at runtime and never stored on the server.

---

## 1. Put the config file in place

Copy the `google-services.json` you downloaded from the Firebase console to:

```
app/google-services.json
```

It must contain the package `com.deeproot.app` and an OAuth client of type 3 (web) — the file for this project does.

## 2. Authentication

Firebase console → **Build → Authentication → Sign-in method**:

1. **Anonymous** → Enable → Save. (Every install starts as a guest so the app works immediately.)
2. **Google** → Enable → choose a support e-mail → Save. This creates the *Web client* OAuth id that the app uses for
   "Sign in with Google" (linking the guest account so progress survives a reinstall).

## 3. Firestore

Firebase console → **Build → Firestore Database → Create database**:

- Mode: **Production** (our rules lock everything down anyway).
- Location: **`asia-south1` (Mumbai)** — cannot be changed later.

## 4. Blaze plan (required for Cloud Functions)

Firebase console → ⚙️ → **Usage and billing → Details & settings → Modify plan → Blaze**. The free tier still applies;
one student's usage stays far below it. Add a budget alert (e.g. ₹100) in Google Cloud Billing to be safe.

> Without Blaze the app still works: seeding falls back to uploading the plan from the phone and calendar sync runs on
> the phone. Only the stats aggregate and the FCM fallback reminders need the functions.

## 5. Firebase CLI and deployment

```bash
npm install -g firebase-tools          # Node 22 recommended
firebase login
cd Detox                                # repository root (contains firebase.json and .firebaserc)
firebase use deeproot-e4d17

# Security rules, composite indexes and the TTL policy for stats idempotency markers
firebase deploy --only firestore:rules,firestore:indexes

# Cloud Functions (first deploy enables Cloud Functions, Cloud Build, Artifact Registry,
# Cloud Scheduler, Eventarc and Pub/Sub APIs — accept the prompts; takes a few minutes)
cd functions && npm ci && cd ..
firebase deploy --only functions
```

Deployed functions:

| Function | Type | What it does |
|---|---|---|
| `seedLectures` | callable | Creates the 479 plan lectures for the signed-in user (never overwrites), verifies every lecture of `lecture_plan.md` exists, sets `planSeeded`. The app calls it once per account. |
| `onLectureCompleted` | Firestore trigger `users/{uid}/lectures/{id}` | Maintains `users/{uid}/stats/summary` (totals, per subject, daily completions). Idempotent. |
| `scheduledReminder` | every 30 min | FCM fallback reminder when the phone did not remind for the current slot (Doze). |
| `syncCalendar` | callable | Imports the next 60 days of the chosen Google Calendar (token passed per call, not stored). |

Optional manual seeding / verification for a user (uid is in Firebase console → Authentication):

```bash
cd functions
gcloud auth application-default login      # or export GOOGLE_APPLICATION_CREDENTIALS=/path/to/key.json (never commit it)
npm run seed:user -- <uid>                 # prints expected / created / totalPlanDocs / verified
```

## 6. SHA-1 fingerprint (Google Sign-In + Calendar)

Google sign-in and the Calendar authorization only work if the **SHA-1 of the key that signed the APK** is registered
for `com.deeproot.app`. Your `google-services.json` already contains an Android OAuth client with SHA-1
`8B:60:5C:3E:E9:6A:B4:EE:DC:F9:79:0E:4A:46:4A:C7:6D:7D:30:05`. Check that it is the key you build with:

```bash
./gradlew signingReport          # look at "Variant: debug" → SHA1
# or
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

If it is different (another computer, a new debug keystore, or a release keystore):

1. Firebase console → ⚙️ **Project settings → Your apps → Lecture Tracker (com.deeproot.app) → Add fingerprint** →
   paste the SHA-1 (add the SHA-256 too).
2. Download the updated `google-services.json` and replace `app/google-services.json`.
3. Rebuild. (Release builds use the debug key by default here; if you sign with your own keystore — or use Play App
   Signing — register those SHA-1s as well.)

Symptoms of a missing/wrong SHA-1: *"Developer console is not set up correctly"*, `[28444]`, `DEVELOPER_ERROR`,
or the Google account sheet closing immediately.

## 7. Google Calendar API + OAuth consent screen

In the **Google Cloud console** for the same project (`console.cloud.google.com`, project `deeproot-e4d17`):

1. **APIs & Services → Library → Google Calendar API → Enable.**
2. **APIs & Services → OAuth consent screen** (Google Auth Platform):
   - User type **External**, app name "Lecture Tracker", support e-mail, developer e-mail.
   - **Data access / Scopes → Add** `https://www.googleapis.com/auth/calendar.readonly`.
   - **Audience → Test users → Add** the Google account(s) whose calendar you will import. While the app is in
     *Testing*, only test users can grant access (others see "Access blocked").
3. **APIs & Services → Credentials** should list an *Android client* for `com.deeproot.app` with your SHA-1 (created
   by Firebase in step 6) and a *Web client* (created in step 2). Nothing to download — the app reads the web client id
   from `google-services.json`.

## 8. Restrict the API key (recommended, public repo)

The API key in `google-services.json` is not a secret (it is in every APK), but restrict it so it is only usable by this app:
Cloud console → **APIs & Services → Credentials → "Android key (auto created by Firebase)"** →

- *Application restrictions*: **Android apps** → add package `com.deeproot.app` + your SHA-1(s).
- *API restrictions*: restrict to the Firebase APIs the app uses — Identity Toolkit API, Token Service API, Cloud
  Firestore API, Firebase Installations API, FCM Registration API (Firebase Cloud Messaging API).

Test the app after restricting (sign-in, sync, reminders).

## 9. Cloud Messaging

Nothing to configure for Android: the FCM token is registered automatically (`users/{uid}.fcmToken`). Make sure
**Firebase Cloud Messaging API (V1)** is enabled (Project settings → Cloud Messaging), which is the default.

## 10. Optional: real config in CI

GitHub → repository **Settings → Secrets and variables → Actions → New repository secret** `GOOGLE_SERVICES_JSON` with
the file contents. CI then builds APKs with your real config instead of the placeholder in `ci/`.

## 11. Optional: rules tests and emulators

```bash
cd firestore-tests && npm ci && npm run test:emulator   # needs Java 11+ (downloads the emulator once)
cd functions && npm test                                # unit tests of the TypeScript logic
```

## Data model

```
users/{uid}                      timeZone, platform, appVersion, fcmToken, planSeeded, planSeededCount,
                                 lastLocalReminderAt, lastPushReminderAt, createdAt, updatedAt
users/{uid}/lectures/{id}        date "yyyy-MM-dd", order int, subject, title, completed, completedAt ts|null,
                                 plannedMinutes int (120), studiedSeconds int, source "plan"|"calendar",
                                 calendarEventId string|null, updatedAt ts
                                 ids: plan_<yyyy-MM-dd>_<NN> (plan), cal_<eventId> (calendar)
users/{uid}/sessions/{id}        lectureId, startedAt ts, endedAt ts, plannedMinutes, studiedSeconds
users/{uid}/settings/preferences reminderIntervalHours 2, reminderStartHour 8, reminderEndHour 22, remindersEnabled,
                                 allowedApps [packages], focusModeEnabled, calendarId, timeZone, updatedAt
users/{uid}/stats/summary        written by onLectureCompleted (read-only for the app)
users/{uid}/statsEvents/{id}     idempotency markers (server only, deleted by TTL after 7 days)
```
