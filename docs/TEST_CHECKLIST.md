# Test checklist

Work through the stages in order. "✅ Expect" is what you should see. Use a real phone for Stages 2–3.
Tip: Settings → *Account & sync* shows the sync state and the last problem, Settings → *Lecture plan* shows the
verification counts.

## Automated (CI runs these on every push)

- [ ] `./gradlew testDebugUnitTest` — Kotlin unit tests: timer logic, plan parser (asserts **479**), reminder rules,
      calendar diff/mapping/subject guessing, merge rules, stats, focus policy.
- [ ] `cd functions && npm ci && npm test` — TypeScript parser (same 479 + same ids), stats deltas, reminder rules,
      calendar diff.
- [ ] `cd firestore-tests && npm ci && npm run test:emulator` — security rules (owner-only, schema, last-writer-wins).

---

## Stage 1 — data, Today, multi-select

Setup: Firebase steps 1–5 done (Auth anonymous, Firestore, rules + indexes deployed; functions optional).

- [ ] **First launch offline** (airplane mode): finish/skip onboarding → Today shows today's lectures immediately
      (the plan is bundled). ✅ Expect an *Offline* banner, 0 of 479 done.
- [ ] Settings → *Lecture plan*: ✅ **"479 lectures · verified ✓"**, 15 Oct – 9 Jan 2027, 5 NO LECTURE days,
      "Parsed 479 of 479 lecture lines".
- [ ] Go online. Within ~10 s Settings → *Account & sync* says *Everything is synced*; *Lecture plan → Firestore*
      says *"Cloud copy verified: 479/479 plan lectures"* (via `seedLectures`) or *"Cloud copy created and verified"*
      (device upload fallback).
- [ ] Firebase console → Firestore → `users/<uid>/lectures`: ✅ 479 documents with ids like `plan_2026-10-15_01`,
      fields exactly as in the data model; `users/<uid>` has `planSeeded: true, planSeededCount: 479`.
- [ ] Tick a lecture → strike-through, green *Completed*, progress bar moves; console shows `completed: true`,
      `completedAt`, a newer `updatedAt`.
- [ ] Date arrows ‹ › move one day; *Jump to today* appears when not on today. Open **28 Oct 2026**: ✅ "NO LECTURE ·
      rest day". Open a date after 9 Jan 2027: ✅ "Past the end of the plan".
- [ ] Backlog: on a later date (or by waiting a day) unfinished earlier lectures appear in the red **Backlog** section,
      grouped by date.
- [ ] **Long-press** a lecture → selection mode (top bar with count). Tap others to add/remove.
      *Select all* selects every visible lecture (day + backlog).
- [ ] *Mark all completed* → snackbar "Marked N completed" → **Undo** restores the previous state.
- [ ] *Delete* → confirmation dialog → snackbar "Deleted N lectures" → **Undo** brings them back. Delete again without
      undo → the documents disappear from Firestore; progress total drops (e.g. 478).
- [ ] Back button in selection mode exits selection.
- [ ] Offline edits: airplane mode → tick 3 lectures → banner "3 changes will sync" → online → console updated.
- [ ] Conflict: change the same lecture on two devices (or console + phone) — the most recent change wins on both.
- [ ] Dark mode (system setting) looks right; rotate the phone; text scaling 130% still usable.

## Stage 2 — timer, Focus mode, onboarding, Stats

- [ ] **Fresh install** → onboarding: Welcome → Notifications → Usage access → Display over other apps → Battery →
      done. ✅ Every step explains *why* first; nothing is requested until **Allow**; *Not now* skips.
- [ ] Revoke *Usage access* in Android settings → return to the app: ✅ red banner on Today "Turned off: Usage access ·
      Fix" → opens the Permissions screen.
- [ ] Settings → *Allowed apps*: installed apps listed with icons, search works, the **PW app is pre-selected** if
      installed; toggling saves immediately.
- [ ] Tap **Start** on a lecture → "How long will this lecture take?" → default **2h 00m**, chips 1.5h/2h/3h/4h,
      +/- for hours and minutes → **Start**.
- [ ] Focus screen: big studied timer counting up, "1:59:5x left of 2h", ring progress, Pause/Resume, *Finish early*.
- [ ] Notification: ongoing *Study session* with a live counting timer and Pause/Finish actions; Pause from the
      notification → screen shows *Paused*; Resume continues from the same time.
- [ ] **Kill the app** (swipe from recents, or `adb shell am kill com.deeproot.app`) → reopen: ✅ the timer shows the
      correct elapsed time and keeps going; the notification is still there.
- [ ] Reboot while paused/running → ✅ the session is restored after boot (paused at shutdown, so the reboot time is not counted).
- [ ] **Focus mode**: with a session running open YouTube/Instagram → ✅ full-screen *"Focus mode: finish your lecture"*
      within ~1 s; **Back to study** returns to the Focus screen; the Back key does the same.
- [ ] Open the PW app (or any allowed app) → ✅ no cover. Phone/dialer, emergency call, Settings, home screen,
      notification shade → ✅ never covered. The overlay's *Phone / emergency call* button opens the dialer.
- [ ] Set a short session (5 min) → at the planned time ✅ "Planned time reached" notification, Focus cover stops,
      timer continues showing "+0:00:xx".
- [ ] **Finish** → back on Today: ✅ snackbar "Completed … · Xm studied", lecture completed, studied time shown;
      Firestore has a `sessions` document and the lecture's `studiedSeconds`.
- [ ] *Stop without completing* → time saved, lecture stays pending.
- [ ] **Stats**: today's study time, this week's bars (today highlighted), 🔥 streak, per-subject completed/total with
      progress bars and studied time. With functions deployed: "☁️ Cloud stats (onLectureCompleted)" line matches.
- [ ] Settings → Focus mode off → starting a session shows no cover.

## Stage 3 — reminders

Setup: notifications allowed; for the server fallback the functions are deployed (Blaze).

- [ ] Settings → Reminders: toggle, *Every 2 h*, *From 08:00*, *Until 22:00*, "Next check around 10:00".
- [ ] **Send a test reminder** → ✅ notification *"N lectures pending. Next: Physics – Rotation 2"* (today's first
      pending lecture, otherwise the oldest backlog lecture). Tap → opens Today.
- [ ] Quick real test: set *Every 1 h* and *From* = current hour → within a minute "Next check" shows the next full
      hour; at that time ✅ one reminder. (A check can run up to 15 min early and still count for the slot.)
- [ ] Complete everything for today with no backlog → ✅ no reminder at the next slot.
- [ ] Outside the window (e.g. *Until* = current hour) → ✅ no reminder.
- [ ] Never two reminders in the same slot, even after reopening the app or rebooting.
- [ ] During a running study session → ✅ no reminder.
- [ ] Reboot the phone → reminders keep coming (schedule restored by the boot receiver).
- [ ] Inspect the schedule (optional): `adb shell dumpsys jobscheduler | grep -A3 com.deeproot.app`.
- [ ] FCM fallback: Firestore `users/<uid>` has `fcmToken`; force Doze
      (`adb shell dumpsys deviceidle force-idle`) over a slot → 30–60 min after the slot the push arrives and the app
      shows the reminder (only if the phone did not already remind for that slot). Function logs:
      `firebase functions:log --only scheduledReminder`.

## Stage 4 — Google Calendar

Setup: setup steps 6–7 (SHA-1, Calendar API, consent screen with you as test user).

- [ ] Settings → **Sign in with Google** → account sheet → ✅ "Signed in as …"; Account shows your e-mail;
      Firebase Authentication shows the same uid now with a Google provider (progress kept).
- [ ] Fresh install on a second phone, sign in with the same Google account → ✅ your progress appears there
      (the guest's offline progress is merged into the account).
- [ ] **Connect Google Calendar** → consent screen (calendar read-only) → *Pick a calendar* → choose one.
      ✅ "Calendar synced: N events · N new…"; calendar lectures appear on their days with a calendar icon and a
      guessed subject chip ("Physics …" → Physics, "GOC" → Organic, unknown → Other).
- [ ] Run *Sync now* again → ✅ "0 new, 0 updated" (no duplicates).
- [ ] Rename / move an event in Google Calendar → *Sync now* → ✅ the lecture's title/date updates; completion kept.
- [ ] Delete an event → *Sync now* → ✅ the lecture disappears — unless it was already completed (then it stays and
      the summary says "1 completed kept").
- [ ] Delete an imported lecture in the app → *Sync now* → ✅ it does not come back.
- [ ] Reopen the app after >10 min → syncs silently; background sync runs every 6 h
      (`adb shell dumpsys jobscheduler | grep calendar`).
- [ ] Revoke access at <https://myaccount.google.com/permissions> → *Sync now* → ✅ "Reconnect" → consent again.
- [ ] Without deployed functions (or offline function) → ✅ sync still works (computed on the phone).
- [ ] *Disconnect* → stops syncing; imported lectures stay.
