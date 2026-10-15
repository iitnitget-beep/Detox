import assert from "node:assert/strict";
import { test } from "node:test";
import { isPristineSeedCreate, localDate, statsDelta, summaryFromLectures } from "../src/stats";

const ts = (ms: number) => ({ toMillis: () => ms });
const IST = "Asia/Kolkata";
// 2026-10-15 20:00 UTC = 2026-10-16 01:30 IST
const LATE_EVENING_UTC = Date.UTC(2026, 9, 15, 20, 0, 0);

const seed = { subject: "Physics", completed: false, completedAt: null, studiedSeconds: 0, updatedAt: ts(0) };

test("pristine seed creations are skipped, real changes are not", () => {
  assert.equal(isPristineSeedCreate(null, seed), true);
  assert.equal(isPristineSeedCreate(null, { ...seed, updatedAt: ts(5) }), false);
  assert.equal(isPristineSeedCreate(seed, { ...seed, completed: true }), false);
  assert.equal(isPristineSeedCreate(seed, null), false);
});

test("completing a lecture increments totals, subject and the local completion day", () => {
  const after = { ...seed, completed: true, completedAt: ts(LATE_EVENING_UTC), studiedSeconds: 6000, updatedAt: ts(LATE_EVENING_UTC) };
  const delta = statsDelta(seed, after, IST);
  assert.ok(delta);
  assert.equal(delta.totalLectures, 0);
  assert.equal(delta.completedLectures, 1);
  assert.equal(delta.studiedSeconds, 6000);
  assert.deepEqual(delta.subjects, { Physics: { total: 0, completed: 1, studiedSeconds: 6000 } });
  assert.deepEqual(delta.daily, { "2026-10-16": { completed: 1 } });
});

test("un-completing reverses the completion", () => {
  const done = { ...seed, completed: true, completedAt: ts(LATE_EVENING_UTC), updatedAt: ts(LATE_EVENING_UTC) };
  const delta = statsDelta(done, { ...seed, updatedAt: ts(LATE_EVENING_UTC + 1) }, IST);
  assert.ok(delta);
  assert.equal(delta.completedLectures, -1);
  assert.deepEqual(delta.daily, { "2026-10-16": { completed: -1 } });
});

test("create and delete change the totals; no-op writes return null", () => {
  const cal = { subject: "Maths", completed: false, studiedSeconds: 0, updatedAt: ts(10) };
  assert.equal(statsDelta(null, cal, IST)?.totalLectures, 1);
  assert.equal(statsDelta(cal, null, IST)?.totalLectures, -1);
  assert.equal(statsDelta(cal, { ...cal, updatedAt: ts(11) }, IST), null);
});

test("subject change moves the lecture between subjects", () => {
  const before = { subject: "Other", completed: false, studiedSeconds: 0, updatedAt: ts(1) };
  const delta = statsDelta(before, { ...before, subject: "Physics", updatedAt: ts(2) }, IST);
  assert.ok(delta);
  assert.equal(delta.subjects.Other.total, -1);
  assert.equal(delta.subjects.Physics.total, 1);
});

test("full recount", () => {
  const summary = summaryFromLectures(
    [
      seed,
      { ...seed, completed: true, completedAt: ts(LATE_EVENING_UTC), studiedSeconds: 60 },
      { subject: "Maths", completed: false, studiedSeconds: 30 },
    ],
    IST,
  );
  assert.equal(summary.totalLectures, 3);
  assert.equal(summary.completedLectures, 1);
  assert.equal(summary.studiedSeconds, 90);
  assert.deepEqual(summary.subjects.Physics, { total: 2, completed: 1, studiedSeconds: 60 });
  assert.deepEqual(summary.daily, { "2026-10-16": { completed: 1 } });
});

test("localDate uses the user's time zone", () => {
  assert.equal(localDate(LATE_EVENING_UTC, IST), "2026-10-16");
  assert.equal(localDate(LATE_EVENING_UTC, "UTC"), "2026-10-15");
});
