# Lecture Tracker (Detox)

Native Android app + Firebase backend for working through a fixed lecture plan:
**479 lectures from 15 Oct 2026 to 9 Jan 2027** (Physics 170 · Organic 79 · Physical Chem 48 · Inorganic 56 ·
Maths 126) with a study timer, a Focus mode that keeps distracting apps away during a session, reminders while
lectures are pending, Google Calendar import and stats.

| | |
|---|---|
| Android | Kotlin, Jetpack Compose (Material 3), MVVM, Hilt, Coroutines/Flow, Room, DataStore, WorkManager, minSdk 26 / targetSdk 34 |
| Backend | Firebase Auth (anonymous → Google), Cloud Firestore, Cloud Functions (TypeScript, Node 22, region `asia-south1`), FCM |
| Package / project | `com.deeproot.app` · Firebase project `deeproot-e4d17` |

## Features

- **Today** — the day's lectures with checkboxes and subject chips, overall progress bar, "Completed" state, a red
  **Backlog** section for older unfinished lectures, date arrows and "Jump to today". *NO LECTURE* days are shown as rest days.
- **Multi-select** — long-press a lecture; the top bar offers *Select all*, *Mark all completed* and *Delete*
  (with confirmation). Both bulk actions can be undone from the snackbar.
- **Start → Focus** — "How long will this lecture take?" (hours + minutes, default 2 h, chips 1.5/2/3/4 h) opens a
  big studied-time timer with the countdown to the planned time, Pause/Resume/Finish. A foreground notification
  shows the live timer; the timer is stored as timestamps and survives the app being killed or the phone rebooting.
  *Finish* marks the lecture completed, adds the studied time and writes a session.
- **Focus mode (Detox)** — only during a running session: any app that is not this app or one of your allowed apps
  (the PW app is pre-selected) is covered by *"Focus mode: finish your lecture"* with a **Back to study** button.
  The phone dialer, emergency calls, Settings and the home screen are never blocked. Turns off when the planned
  time is reached or you finish.
- **Onboarding** — one plain-language explainer per permission (Notifications, Usage access, Display over other apps,
  Battery). Nothing is requested before you tap **Allow**; permissions are re-checked on every launch.
- **Reminders** — every 2 h between 08:00 and 22:00 (configurable) while something is pending today or in the
  backlog: *"3 lectures pending. Next: Physics – Rotation 2"*. Tapping opens Today. Survives reboots; a Cloud
  Function sends an FCM fallback when Android delayed the local reminder (Doze).
- **Google Calendar** — sign in with Google (keeps your progress), pick a calendar, import the next 60 days as
  lectures (subject guessed from the title), deduplicated by event id; changed events update, deleted events are
  removed unless already completed. Syncs on open, on *Sync now* and every 6 h.
- **Stats** — today's study time, weekly bar chart, streak, per-subject totals.
- **Offline-first** — Room is the source of truth for the UI; changes sync to Firestore when online (last writer wins
  by `updatedAt`, enforced by the security rules). Dark mode, large touch targets, loading/empty/error/offline states.

## Quick start

1. Firebase setup (once): **[docs/SETUP_FIREBASE.md](docs/SETUP_FIREBASE.md)** — enable Anonymous + Google sign-in,
   create Firestore in `asia-south1`, deploy rules/indexes/functions, check the SHA-1, enable the Calendar API.
2. Put your `google-services.json` in `app/` (it is git-ignored because this repository is public).
3. Build & install: **[docs/BUILD_AND_INSTALL.md](docs/BUILD_AND_INSTALL.md)** (Android Studio, Gradle JDK 17 or 21).
4. Test: **[docs/TEST_CHECKLIST.md](docs/TEST_CHECKLIST.md)** (step by step, per stage).
5. Design notes: **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** (sync, Focus-mode approach and trade-offs, Doze /
   exact-alarm limits, calendar, security).

## Build stages

| Stage | Contents |
|---|---|
| 1 | Data layer (Room, DataStore, Firestore sync, anonymous auth), plan seeding (479, verified), Today + multi-select, rules, indexes, `seedLectures` |
| 2 | Start dialog, Focus screen + foreground timer service, Focus mode, onboarding/permissions, Stats, `onLectureCompleted` |
| 3 | Reminders (WorkManager + FCM fallback), reminder settings, `scheduledReminder` |
| 4 | Google Sign-In, Google Calendar import/sync, `syncCalendar` |

CI (GitHub Actions) builds debug + release APKs, runs the Kotlin unit tests, the Cloud Functions tests and the
Firestore security-rules tests against the emulator on every push.

## The lecture plan

`app/src/main/assets/lecture_plan.md` is the single source of truth (`## yyyy-MM-dd (Ddd)` headings,
`Subject: Title N` lines, `NO LECTURE` days). The app seeds Room from it on first launch, and the `seedLectures`
function deploys with a copy of the same file (`functions/scripts/copy-plan.mjs`). Both parsers verify that the number
of parsed lectures equals the number of lecture lines (479), and the function verifies every lecture exists in
Firestore afterwards. `tools/convert_html_plan.mjs` regenerates the file from the original prototype
(`docs/source/Lecture Tracker.html`).

## File tree

```
.
├── .github/workflows/ci.yml          CI: Android build + unit tests, functions tests, rules tests (emulator)
├── app/
│   ├── build.gradle.kts              Android app module (com.deeproot.app)
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/lecture_plan.md            the 479-lecture plan (single source of truth)
│       │   ├── java/com/deeproot/app/
│       │   │   ├── LectureTrackerApp.kt          Hilt app, WorkManager config, starts sync + reminders
│       │   │   ├── MainActivity.kt               Compose host, deep links from notifications
│       │   │   ├── core/                         pure Kotlin logic (unit tested)
│       │   │   │   ├── calendar/                 CalendarDiff, CalendarEventMapper, SubjectGuesser
│       │   │   │   ├── focus/FocusPolicy.kt      what Focus mode may block (never dialer/emergency/settings)
│       │   │   │   ├── model/                    Lecture, StudySession, ActiveSession, Subject, AppSettings
│       │   │   │   ├── plan/PlanParser.kt        plan file parser + deterministic ids
│       │   │   │   ├── reminder/ReminderRules.kt slots, window, one reminder per slot, message text
│       │   │   │   ├── stats/StatsCalculator.kt  today, week, streak, per subject
│       │   │   │   ├── sync/LectureMerger.kt     last-writer-wins merge
│       │   │   │   ├── time/                     TimeSource, Dates
│       │   │   │   └── timer/TimerState.kt       timestamp-based timer + formatting
│       │   │   ├── data/
│       │   │   │   ├── apps/                     installed apps for the allowed-apps picker
│       │   │   │   ├── auth/AuthRepository.kt    anonymous auth, Google linking
│       │   │   │   ├── calendar/                 Calendar REST API (Retrofit) + CalendarRepository
│       │   │   │   ├── local/                    Room entities, DAOs, database, mappers
│       │   │   │   ├── prefs/                    DataStore settings + local flags
│       │   │   │   ├── remote/                   Firestore + Functions data sources, mappers
│       │   │   │   ├── repository/               Lecture, Session, Settings repositories
│       │   │   │   ├── seed/PlanSeeder.kt        asset → Room, verification
│       │   │   │   └── sync/                     SyncManager, SyncWorker, NetworkMonitor, SyncTrigger
│       │   │   ├── di/                           Hilt modules (app, network)
│       │   │   ├── focus/                        StudySessionService (FGS), overlay, foreground-app detector
│       │   │   ├── calendar/                     GoogleAccountManager (Credential Manager + Calendar auth), worker
│       │   │   ├── notifications/                channels, intents, boot receiver
│       │   │   ├── permissions/PermissionChecker.kt
│       │   │   ├── reminders/                    ReminderScheduler, ReminderWorker, ReminderNotifier, FCM service
│       │   │   └── ui/                           Compose screens: today, focus, stats, settings, onboarding,
│       │   │                                     permissions, navigation, components, theme
│       │   └── res/                              themes (light/dark), icons, overlay layout, strings
│       └── test/java/com/deeproot/app/core/      JUnit tests (timer, plan parser, reminders, calendar, merge, stats)
├── ci/                                           CI helper script + placeholder google-services.json
├── docs/                                         setup, build, test checklist, architecture; original prototype
├── firebase.json · .firebaserc                   Firebase CLI config (project deeproot-e4d17)
├── firestore.rules · firestore.indexes.json      security rules (owner-only, strict schema) and indexes
├── firestore-tests/                              security-rules tests (@firebase/rules-unit-testing + emulator)
├── functions/                                    Cloud Functions (TypeScript)
│   ├── src/index.ts                              exports seedLectures, onLectureCompleted, scheduledReminder, syncCalendar
│   ├── src/seedLectures.ts · seed.ts             callable seeder + shared seeding/verification
│   ├── src/onLectureCompleted.ts · stats.ts · summary.ts   stats aggregates
│   ├── src/scheduledReminder.ts · reminderRules.ts         FCM fallback reminders
│   ├── src/syncCalendar.ts · calendar/                     server-side calendar import
│   ├── src/plan/                                 TypeScript plan parser (same ids as the app)
│   ├── src/scripts/seedUser.ts                   CLI: npm run seed:user -- <uid>
│   └── test/                                     node:test unit tests
├── tools/convert_html_plan.mjs                   prototype HTML → lecture_plan.md
└── gradle/ · gradlew · settings.gradle.kts · build.gradle.kts · gradle.properties
```
