import assert from "node:assert/strict";
import { test } from "node:test";
import { addDays, CalendarEventInput, computeDiff, ExistingCalendarLecture, lectureIdFor, toEventInput, toFields } from "../src/calendar/diff";
import { guessSubject } from "../src/calendar/subject";

// Mirrors app/src/test/java/com/deeproot/app/core/calendar/CalendarSyncLogicTest.kt

const ev = (id: string, title: string, date: string, start: number | null = 600, duration: number | null = 90, cancelled = false): CalendarEventInput =>
  ({ eventId: id, title, date, startMinuteOfDay: start, durationMinutes: duration, cancelled });

test("subject guessing from keywords", () => {
  assert.equal(guessSubject("PW Physics - Rotation 3"), "Physics");
  assert.equal(guessSubject("GOC lecture"), "Organic");
  assert.equal(guessSubject("Inorganic: P Block"), "Inorganic");
  assert.equal(guessSubject("Thermodynamics revision"), "Physical Chem");
  assert.equal(guessSubject("Matrix + Determinant"), "Maths");
  assert.equal(guessSubject("Chemistry doubt class"), "Physical Chem");
  assert.equal(guessSubject("Dentist"), "Other");
  assert.equal(guessSubject(""), "Other");
});

test("inorganic is not mistaken for organic; whole words only", () => {
  assert.equal(guessSubject("inorganic chemistry"), "Inorganic");
  assert.equal(guessSubject("Wavelength party"), "Other");
  assert.equal(guessSubject("Accounting"), "Other");
});

test("fields derived from an event", () => {
  const f = toFields(ev("abc", "Physics: Optics 2", "2026-10-20", 630, 75));
  assert.deepEqual(f, { id: "cal_abc", calendarEventId: "abc", date: "2026-10-20", order: 1630, subject: "Physics", title: "Physics: Optics 2", plannedMinutes: 75 });
  const allDay = toFields(ev("d", "Maths test", "2026-10-21", null, null));
  assert.equal(allDay.order, 1000);
  assert.equal(allDay.plannedMinutes, 120);
  assert.equal(toFields(ev("x", " ", "2026-10-21", 0, 5)).plannedMinutes, 15);
  assert.equal(toFields(ev("x", "a".repeat(500), "2026-10-21", 0, 10000)).plannedMinutes, 720);
  assert.equal(toFields(ev("x", "a".repeat(500), "2026-10-21")).title.length, 200);
  assert.equal(lectureIdFor("a b/c@d"), "cal_a_b_c_d");
});

test("diff: insert once, update keeps id, delete only uncompleted, window respected", () => {
  const existing: ExistingCalendarLecture[] = [
    { ...toFields(ev("keep", "Physics: Rotation 1", "2026-10-20")), completed: false },
    { ...toFields(ev("moved", "Maths: Matrix 1", "2026-10-20")), id: "cal_moved", completed: true },
    { ...toFields(ev("gone", "Organic: GOC 1", "2026-10-21")), completed: false },
    { ...toFields(ev("goneDone", "Organic: GOC 2", "2026-10-21")), completed: true },
    { ...toFields(ev("past", "Physics: Old", "2026-10-01")), completed: false },
  ];
  const events = [
    ev("keep", "Physics: Rotation 1", "2026-10-20"),
    ev("moved", "Maths: Matrix 1", "2026-10-22"),
    ev("new", "Inorganic: S Block 1", "2026-10-23"),
    ev("new", "Inorganic: S Block 1", "2026-10-23"),
    ev("cancelled", "Physics: X", "2026-10-24", 600, 60, true),
    ev("far", "Physics: Far", "2027-03-01"),
  ];
  const diff = computeDiff(events, existing, "2026-10-15", addDays("2026-10-15", 60));
  assert.deepEqual(diff.inserts.map((f) => f.id), ["cal_new"]);
  assert.deepEqual(diff.updates.map((f) => [f.id, f.date]), [["cal_moved", "2026-10-22"]]);
  assert.deepEqual(diff.deletes, ["cal_gone"]);
  assert.equal(diff.keptCompleted, 1);
});

test("Google events are converted to the user's local date and time", () => {
  const timed = toEventInput(
    { id: "e1", summary: "Physics", start: { dateTime: "2026-10-20T04:30:00Z" }, end: { dateTime: "2026-10-20T06:00:00Z" } },
    "Asia/Kolkata",
  );
  assert.deepEqual(timed, { eventId: "e1", title: "Physics", date: "2026-10-20", startMinuteOfDay: 600, durationMinutes: 90, cancelled: false });
  const allDay = toEventInput({ id: "e2", summary: "Holiday", start: { date: "2026-10-21" }, end: { date: "2026-10-22" } }, "Asia/Kolkata");
  assert.deepEqual(allDay, { eventId: "e2", title: "Holiday", date: "2026-10-21", startMinuteOfDay: null, durationMinutes: null, cancelled: false });
  assert.equal(toEventInput({ summary: "no id" }, "UTC"), null);
  assert.equal(addDays("2026-12-31", 1), "2027-01-01");
});
