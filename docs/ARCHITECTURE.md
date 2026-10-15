# Architecture & design decisions

## Layers

```
ui (Compose screens + ViewModels, MVVM, StateFlow)
  │
data (repositories, Room, DataStore, Firestore/Functions/Calendar data sources, SyncManager, workers)
  │
core (pure Kotlin: PlanParser, TimerState, ReminderRules, CalendarDiff, LectureMerger, StatsCalculator, FocusPolicy)
```

- **core** has no Android dependencies and carries the logic that matters most; it is covered by JVM unit tests and
  mirrored 1:1 in TypeScript where the server needs the same rules (plan parser + ids, reminder slots, calendar diff,
  subject guessing).
- **Hilt** wires everything (`di/AppModule.kt`, `di/NetworkModule.kt`); workers use `@HiltWorker`, services
  `@AndroidEntryPoint`, broadcast receivers `EntryPointAccessors`.
- **Android components**: `StudySessionService` (foreground service, timer + Focus mode), `ReminderWorker`,
  `SyncWorker`, `CalendarSyncWorker`, `ReminderMessagingService` (FCM), `BootReceiver`, `SessionActionReceiver`.

## Offline-first sync

Room is the only thing the UI reads. Every write goes to Room first with a fresh `updatedAt` and `dirty = true`
(deletes are tombstones), then:

1. **Push** — `SyncManager.pushDirty()` sends dirty rows in Firestore batches of ≤ 400 writes; rows are marked clean
   only if they were not edited again meanwhile (`markClean(id, updatedAt)`). Triggered immediately while the app runs
   and through a unique `SyncWorker` (network constraint) so changes made offline or right before the process dies
   are uploaded later.
2. **Bootstrap** (once per app process and account): ensure `users/{uid}` → if the account is not seeded, call
   `seedLectures` (fallback: the phone uploads the plan itself) → full server read → merge → push → settings, sessions,
   FCM token.
3. **Live updates** — while the app is in the foreground a snapshot listener on `users/{uid}/lectures` merges every
   server snapshot (changes from other devices and from `syncCalendar`). Snapshots that may predate our own pending
   writes are applied without deletions.

**Conflict resolution** (`core/sync/LectureMerger.kt`): last writer wins by `updatedAt`. A dirty local row is kept
(and pushed) if it is at least as new as the server version; a clean row follows the server unless the server version
is strictly older (stale snapshot). The **security rules enforce** `request.resource.data.updatedAt >=
resource.data.updatedAt` on updates, so a stale offline write can never overwrite a newer one; if the server rejects a
write, the app fetches the server version and keeps it.

Plan seeds use `updatedAt = 0` on the phone *and* on the server, so seeding is idempotent and any real edit wins.

**Accounts**: every install starts with Firebase **anonymous** auth (works instantly, no sign-up). "Sign in with
Google" links the Google credential to the same uid (progress kept). If that Google account already has data, the app
signs in to it and re-uploads the phone's rows into it (merge by the same rules).

## Seeding & verification (479)

- The plan lives in `app/src/main/assets/lecture_plan.md`. `PlanParser` (Kotlin) and `functions/src/plan/parser.ts`
  produce identical lectures and ids `plan_<yyyy-MM-dd>_<NN>` (tests on both sides assert 479 lectures, 82 lecture
  days, the 5 NO LECTURE dates and the per-subject counts).
- **Local**: `PlanSeeder` checks *parsed lectures == lecture lines in the file* and, after inserting, *plan rows in
  Room ≥ parsed count*. Failures are shown on Today with a retry.
- **Server**: `seedLectures` creates only missing documents (never overwrites progress), then lists the plan documents
  and verifies that **every** lecture of the file exists before setting `planSeeded`. The response
  (`expected/created/alreadyPresent/totalPlanDocs/verified`) is shown in Settings. A CLI (`npm run seed:user`) does
  the same for a given uid.

## Study timer

`TimerState` stores only `plannedMillis`, `accumulatedMillis` and `runningSinceMillis` (wall clock). Studied time is
always derived from "now", so it is exact after the process is killed, the screen is off or the phone sleeps. The
running session is a single Room row (`active_session`). On `ACTION_SHUTDOWN` the service pauses the session so a
reboot is not counted as study time; `BootReceiver` and app start bring the foreground service back. The notification
uses the system chronometer (`setUsesChronometer`) so it counts live without waking the app every second.

Finishing writes a `sessions` document, adds the time to the lecture's `studiedSeconds` and (for *Finish*) marks it
completed — all offline-first.

## Focus mode: UsageStats + overlay vs. Accessibility service

Focus mode runs inside `StudySessionService` only while a session is active (and only until the planned time is
reached): every ~700 ms while the screen is on it asks `UsageStatsManager.queryEvents()` for the most recently resumed
app; if `FocusPolicy` says it must be blocked, `FocusOverlay` draws a full-screen `TYPE_APPLICATION_OVERLAY` window
with **Back to study** (and a *Phone / emergency call* button).

| | **UsageStats + overlay (chosen)** | Accessibility service |
|---|---|---|
| Detection | Polling (~0.7 s); the blocked app may be visible for a moment | Event-driven, instant (`TYPE_WINDOW_STATE_CHANGED`) |
| Enforcement | Cover window above apps, below system bars | Can also press Home/Back (`performGlobalAction`) |
| Permissions | *Usage access* + *Display over other apps* | Accessibility — reads all screen content |
| Google Play | Allowed for this use case | Policy restricts Accessibility API to accessibility tools; needs a declaration and review |
| Android 13+ sideloading | Works | "Restricted setting": users must unlock it in App info first |
| Battery | Polling only during sessions with the screen on (pauses when the screen is off) | Event-driven |
| Trust | Narrow data (package names only) | Can read everything on screen — scary for users |

We chose UsageStats + overlay because it needs far less sensitive access, is acceptable on Google Play and works on
sideloaded installs without the restricted-settings dance. The cost is ~0.7 s detection latency and slightly more
battery during sessions. The service re-checks both permissions every 5 s and removes the cover immediately if one is
revoked, so a stale state can never trap the user.

**Never blocked** (`FocusPolicy.ALWAYS_ALLOWED_PREFIXES` + `DeviceAllowedApps`, resolved at runtime): this app,
the user's allowed apps (PW pre-selected), the default dialer, in-call UI and emergency apps, Settings and the
permission controller, all launchers (Home/Recents), the system UI, the keyboard and the clock (alarms). The overlay
sits below the status and navigation bars, so Home, Recents and the notification shade always work.

## Reminders, Doze and exact alarms

- **Rules** (`ReminderRules`, mirrored in `functions/src/reminderRules.ts`): slots at `startHour + k·interval`
  strictly before `endHour` (default 08:00, 10:00 … 20:00); at most one reminder per slot; a check up to 15 min early
  counts for the upcoming slot; never outside the window; only if something is pending today or in the backlog; text
  *"3 lectures pending. Next: Physics – Rotation 2"* (today first, then the oldest backlog lecture). Skipped during a
  study session.
- **Scheduling**: a one-time `ReminderWorker` is enqueued for the next slot and re-chains itself after each run; an
  hourly periodic run is a safety net. Rescheduled on settings changes, boot, app update and time/time-zone changes.
- **Doze / App Standby**: WorkManager runs on JobScheduler, which defers work to maintenance windows while the phone is
  idle (minutes in light Doze, up to hours in deep Doze; worse on aggressive OEM ROMs). *Exact* alarms
  (`setExactAndAllowWhileIdle`) would need `SCHEDULE_EXACT_ALARM` (denied by default for new installs on Android 14)
  or `USE_EXACT_ALARM` (Google Play allows it only for alarm-clock/calendar apps) — not appropriate for nudges.
  Mitigations: the onboarding asks for **unrestricted battery** (exempt from most Doze restrictions), and the
  **`scheduledReminder` Cloud Function** (every 30 min) sends a high-priority, data-only FCM message when the phone
  did not record a reminder for the slot after a 30-minute grace period. High-priority FCM can wake the app from Doze;
  the app re-checks with local data and shows the notification itself, so a reminder is never duplicated.
  Force-stopped apps receive neither WorkManager jobs nor FCM until opened again (Android rule).

## Google Calendar

- **Sign-in**: Credential Manager *Sign in with Google* → Firebase `linkWithCredential` (keeps the anonymous uid).
- **Authorization**: Google Identity Services `AuthorizationClient` for `calendar.readonly`. It returns a short-lived
  access token silently after the first consent (also in background workers) — no refresh token is stored anywhere.
  If consent is needed again, Settings shows **Reconnect**.
- **Sync** (on open if older than 10 min, *Sync now*, every 6 h): preferred path is the `syncCalendar` callable — the
  app sends the access token for that call; the function reads the next 60 days (`singleEvents=true`, paginated),
  computes the diff and writes Firestore; the listener brings the changes into Room. If the function is not deployed or
  unreachable, the same diff runs on the phone against Room. Pending local changes are pushed before a server sync.
- **Diff**: lecture id `cal_<eventId>` (dedupe), date from the event start in the user's time zone, order
  `1000 + start minute` (after plan lectures), planned minutes = event length (15–720, all-day → 120), subject guessed
  from keywords (explicit subject words, then chapter names from the plan). Changed events update title/date/time/
  subject only — completion and studied time are kept. Vanished or cancelled events are deleted unless completed.
  Lectures outside the window are never touched. Lectures you delete in the app are remembered and not re-imported.

## Stats

The Stats screen is computed locally (`StatsCalculator`) from Room, so it works offline and includes every device.
Streak = consecutive days with at least one completed lecture (NO LECTURE days don't break it; today not finished yet
doesn't break it). `onLectureCompleted` additionally maintains `users/{uid}/stats/summary` on the server
(incremental, idempotent via event-id markers with a TTL; the 479 seed creations are covered by one recount instead
of 479 transactions) — shown as the "☁️ Cloud stats" line.

## Security

- `firestore.rules`: everything lives under `users/{uid}`; only the signed-in owner can read/write it; every document
  type has an exact field whitelist with type and range checks; lecture updates cannot move `updatedAt` backwards;
  `stats` is read-only and `statsEvents` server-only. Covered by `firestore-tests/`.
- No secrets in the repository: `google-services.json` is git-ignored, the OAuth web client id comes from it at build
  time, Calendar tokens are short-lived and never persisted on the server, no service-account keys are committed.
- Restrict the Firebase API key to the Android app (see SETUP_FIREBASE.md § 8).

## Known limitations

- Focus-mode detection is polling-based (see the table above).
- Reminders can be delayed by Doze/OEM battery managers; the FCM fallback needs the Blaze plan.
- The calendar import is one-way (Google Calendar → app) and read-only.
- Plan lectures are fixed by `lecture_plan.md`; to change the plan, edit the file, bump `PlanSeeder.PLAN_VERSION` and
  redeploy the functions.
