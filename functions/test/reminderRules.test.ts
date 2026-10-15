import assert from "node:assert/strict";
import { test } from "node:test";
import {
  buildMessage,
  effectiveSlot,
  isWithinWindow,
  localTime,
  sanitize,
  serverDecision,
  slotMinutes,
  wasRemindedThisSlot,
} from "../src/reminderRules";

// Mirrors app/src/test/java/com/deeproot/app/core/reminder/ReminderRulesTest.kt

const DEFAULTS = { enabled: true, intervalHours: 2, startHour: 8, endHour: 22 };
const at = (date: string, h: number, m = 0) => ({ date, minute: h * 60 + m });
const D = "2026-10-15";

test("message matches the required format", () => {
  assert.equal(buildMessage(3, { subject: "Physics", title: "Rotation 2" }), "3 lectures pending. Next: Physics – Rotation 2");
  assert.equal(buildMessage(1, { subject: "Maths", title: "Matrix 1" }), "1 lecture pending. Next: Maths – Matrix 1");
  assert.equal(buildMessage(0, null), null);
});

test("default slots are every two hours from 8 to 20", () => {
  assert.deepEqual(slotMinutes(DEFAULTS).map((m) => m / 60), [8, 10, 12, 14, 16, 18, 20]);
});

test("window boundaries", () => {
  assert.equal(isWithinWindow(at(D, 7, 59), DEFAULTS), false);
  assert.equal(isWithinWindow(at(D, 8), DEFAULTS), true);
  assert.equal(isWithinWindow(at(D, 21, 59), DEFAULTS), true);
  assert.equal(isWithinWindow(at(D, 22), DEFAULTS), false);
});

test("early check within the tolerance counts for the upcoming slot", () => {
  assert.equal(effectiveSlot(at(D, 9, 50), DEFAULTS), 10 * 60);
  assert.equal(effectiveSlot(at(D, 9, 40), DEFAULTS), 8 * 60);
});

test("one reminder per slot; yesterday does not block today", () => {
  assert.equal(wasRemindedThisSlot(at(D, 10, 40), at(D, 10, 2), DEFAULTS), true);
  assert.equal(wasRemindedThisSlot(at(D, 10, 40), at(D, 9, 50), DEFAULTS), true);
  assert.equal(wasRemindedThisSlot(at(D, 10, 40), at(D, 8, 5), DEFAULTS), false);
  assert.equal(wasRemindedThisSlot(at(D, 10, 40), at("2026-10-14", 20, 0), DEFAULTS), false);
});

test("server waits for the grace period and respects the phone's reminder", () => {
  assert.deepEqual(serverDecision(at(D, 10, 10), DEFAULTS, null), { send: false, reason: "grace-period" });
  assert.deepEqual(serverDecision(at(D, 10, 35), DEFAULTS, null), { send: true, slot: 600 });
  assert.deepEqual(serverDecision(at(D, 10, 35), DEFAULTS, at(D, 10, 1)), { send: false, reason: "already-reminded" });
  assert.deepEqual(serverDecision(at(D, 7, 30), DEFAULTS, null), { send: false, reason: "outside-hours" });
  assert.deepEqual(serverDecision(at(D, 10, 35), { ...DEFAULTS, enabled: false }, null), { send: false, reason: "disabled" });
  assert.deepEqual(serverDecision(at(D, 10, 35), { ...DEFAULTS, startHour: 22, endHour: 8 }, null), { send: false, reason: "invalid-window" });
});

test("custom interval and a window ending at midnight", () => {
  const s = { enabled: true, intervalHours: 3, startHour: 9, endHour: 24 };
  assert.deepEqual(slotMinutes(s).map((m) => m / 60), [9, 12, 15, 18, 21]);
  assert.equal(isWithinWindow(at(D, 23, 59), s), true);
});

test("values are clamped to the allowed ranges", () => {
  assert.deepEqual(sanitize({ enabled: true, intervalHours: 0, startHour: -3, endHour: 30 }), {
    enabled: true,
    intervalHours: 1,
    startHour: 0,
    endHour: 24,
  });
});

test("localTime converts to the user's time zone", () => {
  const instant = Date.UTC(2026, 9, 15, 4, 35); // 10:05 IST
  assert.deepEqual(localTime(instant, "Asia/Kolkata"), { date: "2026-10-15", minute: 10 * 60 + 5 });
  assert.deepEqual(localTime(Date.UTC(2026, 9, 15, 20, 0), "Asia/Kolkata"), { date: "2026-10-16", minute: 90 });
});
