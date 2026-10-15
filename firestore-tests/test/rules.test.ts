import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  RulesTestEnvironment,
} from "@firebase/rules-unit-testing";
import { deleteDoc, doc, getDoc, setDoc, Timestamp } from "firebase/firestore";
import { readFileSync } from "node:fs";
import * as path from "node:path";
import { after, before, beforeEach, describe, test } from "node:test";

// Run with:  npm run test:emulator   (starts the Firestore emulator, then runs these tests)

let env: RulesTestEnvironment;

const ALICE = "alice";
const BOB = "bob";

function lecture(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    date: "2026-10-15",
    order: 1,
    subject: "Physics",
    title: "Centre of Mass 1",
    completed: false,
    completedAt: null,
    plannedMinutes: 120,
    studiedSeconds: 0,
    source: "plan",
    calendarEventId: null,
    updatedAt: Timestamp.fromMillis(1_000),
    ...overrides,
  };
}

function session(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    lectureId: "plan_2026-10-15_01",
    startedAt: Timestamp.fromMillis(1_000),
    endedAt: Timestamp.fromMillis(2_000),
    plannedMinutes: 120,
    studiedSeconds: 1,
    ...overrides,
  };
}

function settings(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    reminderIntervalHours: 2,
    reminderStartHour: 8,
    reminderEndHour: 22,
    remindersEnabled: true,
    allowedApps: ["xyz.penpencil.physicswala"],
    focusModeEnabled: true,
    calendarId: null,
    timeZone: "Asia/Kolkata",
    updatedAt: Timestamp.fromMillis(1_000),
    ...overrides,
  };
}

const db = (uid: string | null) => (uid ? env.authenticatedContext(uid).firestore() : env.unauthenticatedContext().firestore());

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-lecture-tracker",
    firestore: { rules: readFileSync(path.join(__dirname, "..", "..", "firestore.rules"), "utf8") },
  });
});

after(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
});

describe("lectures", () => {
  test("owner can create, read and delete a valid lecture", async () => {
    const ref = doc(db(ALICE), "users/alice/lectures/plan_2026-10-15_01");
    await assertSucceeds(setDoc(ref, lecture()));
    await assertSucceeds(getDoc(ref));
    await assertSucceeds(deleteDoc(ref));
  });

  test("calendar lecture with event id is valid", async () => {
    const ref = doc(db(ALICE), "users/alice/lectures/cal_abc123");
    await assertSucceeds(setDoc(ref, lecture({ source: "calendar", calendarEventId: "abc123", subject: "Other", order: 1540 })));
  });

  test("other users and anonymous requests are denied", async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), "users/alice/lectures/l1"), lecture());
    });
    await assertFails(getDoc(doc(db(BOB), "users/alice/lectures/l1")));
    await assertFails(setDoc(doc(db(BOB), "users/alice/lectures/l2"), lecture()));
    await assertFails(getDoc(doc(db(null), "users/alice/lectures/l1")));
    await assertFails(deleteDoc(doc(db(BOB), "users/alice/lectures/l1")));
  });

  test("invalid data is rejected", async () => {
    const ref = doc(db(ALICE), "users/alice/lectures/l1");
    await assertFails(setDoc(ref, lecture({ subject: "Biology" })));
    await assertFails(setDoc(ref, lecture({ order: "1" })));
    await assertFails(setDoc(ref, lecture({ date: "15/10/2026" })));
    await assertFails(setDoc(ref, lecture({ title: "" })));
    await assertFails(setDoc(ref, lecture({ plannedMinutes: 0 })));
    await assertFails(setDoc(ref, lecture({ studiedSeconds: -5 })));
    await assertFails(setDoc(ref, lecture({ completed: "yes" })));
    await assertFails(setDoc(ref, lecture({ source: "manual" })));
    await assertFails(setDoc(ref, lecture({ updatedAt: 1000 })));
    await assertFails(setDoc(ref, lecture({ completedAt: "today" })));
    await assertFails(setDoc(ref, lecture({ unexpected: true })));
    const missingTitle = lecture();
    delete missingTitle.title;
    await assertFails(setDoc(ref, missingTitle));
  });

  test("updates must not move updatedAt backwards", async () => {
    const ref = doc(db(ALICE), "users/alice/lectures/l1");
    await assertSucceeds(setDoc(ref, lecture({ updatedAt: Timestamp.fromMillis(5_000) })));
    await assertFails(setDoc(ref, lecture({ completed: true, updatedAt: Timestamp.fromMillis(4_000) })));
    await assertSucceeds(setDoc(ref, lecture({ completed: true, completedAt: Timestamp.fromMillis(5_000), updatedAt: Timestamp.fromMillis(5_000) })));
    await assertSucceeds(setDoc(ref, lecture({ completed: false, updatedAt: Timestamp.fromMillis(6_000) })));
  });

  test("plan seeds with updatedAt = epoch 0 are accepted", async () => {
    await assertSucceeds(setDoc(doc(db(ALICE), "users/alice/lectures/plan_2026-10-15_01"), lecture({ updatedAt: Timestamp.fromMillis(0) })));
  });
});

describe("sessions", () => {
  test("valid session can be written by the owner only", async () => {
    await assertSucceeds(setDoc(doc(db(ALICE), "users/alice/sessions/s1"), session()));
    await assertFails(setDoc(doc(db(BOB), "users/alice/sessions/s2"), session()));
  });

  test("invalid sessions are rejected", async () => {
    const ref = doc(db(ALICE), "users/alice/sessions/s1");
    await assertFails(setDoc(ref, session({ endedAt: Timestamp.fromMillis(500) })));
    await assertFails(setDoc(ref, session({ plannedMinutes: 2000 })));
    await assertFails(setDoc(ref, session({ extra: 1 })));
  });
});

describe("settings", () => {
  test("valid preferences document", async () => {
    await assertSucceeds(setDoc(doc(db(ALICE), "users/alice/settings/preferences"), settings()));
  });

  test("reminder window and document id are validated", async () => {
    await assertFails(setDoc(doc(db(ALICE), "users/alice/settings/preferences"), settings({ reminderStartHour: 22, reminderEndHour: 8 })));
    await assertFails(setDoc(doc(db(ALICE), "users/alice/settings/preferences"), settings({ reminderIntervalHours: 0 })));
    await assertFails(setDoc(doc(db(ALICE), "users/alice/settings/preferences"), settings({ allowedApps: "all" })));
    await assertFails(setDoc(doc(db(ALICE), "users/alice/settings/other"), settings()));
    await assertFails(setDoc(doc(db(BOB), "users/alice/settings/preferences"), settings()));
  });
});

describe("user document and server-only data", () => {
  test("owner can write known user fields only", async () => {
    const ref = doc(db(ALICE), "users/alice");
    await assertSucceeds(
      setDoc(ref, {
        timeZone: "Asia/Kolkata",
        platform: "android",
        appVersion: "1.0.0",
        fcmToken: "token",
        planSeeded: true,
        planSeededCount: 479,
        createdAt: Timestamp.fromMillis(1_000),
        updatedAt: Timestamp.fromMillis(1_000),
      }),
    );
    await assertFails(setDoc(ref, { isAdmin: true }, { merge: true }));
    await assertFails(setDoc(doc(db(BOB), "users/alice"), { timeZone: "UTC" }, { merge: true }));
    await assertFails(deleteDoc(ref));
  });

  test("stats are read-only and statsEvents are private to the server", async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), "users/alice/stats/summary"), { completedLectures: 1 });
      await setDoc(doc(ctx.firestore(), "users/alice/statsEvents/e1"), { expireAt: Timestamp.fromMillis(1) });
    });
    await assertSucceeds(getDoc(doc(db(ALICE), "users/alice/stats/summary")));
    await assertFails(setDoc(doc(db(ALICE), "users/alice/stats/summary"), { completedLectures: 999 }));
    await assertFails(getDoc(doc(db(BOB), "users/alice/stats/summary")));
    await assertFails(getDoc(doc(db(ALICE), "users/alice/statsEvents/e1")));
  });

  test("unknown top-level collections are denied", async () => {
    await assertFails(setDoc(doc(db(ALICE), "public/anything"), { a: 1 }));
  });
});
