import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { PLAN_FILE, loadPlan } from "../src/plan/loadPlan";
import { isVerified, parsePlan, planLectureId, safeDate } from "../src/plan/parser";
import { planLectureDoc } from "../src/seed";

// Mirrors app/src/test/java/com/deeproot/app/core/plan/PlanParserTest.kt so both parsers stay identical.

test("bundled plan parses every lecture and the count matches the file", () => {
  const text = readFileSync(PLAN_FILE, "utf8");
  const plan = parsePlan(text);
  const linesInFile = text.split(/\r?\n/).filter((l) => /^(Physics|Organic|Physical Chem|Inorganic|Maths): .+$/.test(l.trim())).length;
  assert.equal(linesInFile, 479);
  assert.equal(plan.lectures.length, linesInFile);
  assert.equal(plan.lectureLineCount, linesInFile);
  assert.ok(isVerified(plan));
  assert.deepEqual(plan.warnings, []);
});

test("bundled plan has the expected date range and NO LECTURE days", () => {
  const plan = loadPlan();
  assert.equal(plan.lectures[0].date, "2026-10-15");
  assert.equal(plan.lectures[plan.lectures.length - 1].date, "2027-01-09");
  assert.equal(new Set(plan.lectures.map((l) => l.date)).size, 82);
  assert.deepEqual(plan.noLectureDates, ["2026-10-28", "2026-10-31", "2026-11-02", "2026-11-05", "2026-11-17"]);
});

test("bundled plan subject totals", () => {
  const counts: Record<string, number> = {};
  for (const l of loadPlan().lectures) counts[l.subject] = (counts[l.subject] ?? 0) + 1;
  assert.deepEqual(counts, { Physics: 170, Organic: 79, "Physical Chem": 48, Inorganic: 56, Maths: 126 });
});

test("first day is parsed in order with deterministic ids", () => {
  const plan = loadPlan();
  const first = plan.lectures.filter((l) => l.date === "2026-10-15");
  assert.equal(first.length, 6);
  assert.deepEqual(first[0], { id: "plan_2026-10-15_01", date: "2026-10-15", order: 1, subject: "Physics", title: "Centre of Mass 1" });
  assert.equal(first[5].subject, "Maths");
  assert.equal(first[5].title, "Permutation & Combination 1");
  assert.equal(first[5].id, "plan_2026-10-15_06");
  assert.equal(new Set(plan.lectures.map((l) => l.id)).size, plan.lectures.length);
});

test("accepts several heading styles, bullets and bold", () => {
  const text = [
    "# My plan — 15 Oct 2026 to 9 Jan 2027",
    "Some intro text that mentions NO LECTURE days.",
    "",
    "### Thursday, 15 Oct 2026",
    "- **Physics:** Rotation 2",
    "* Maths: Matrix 1",
    "",
    "Oct 16, 2026",
    "1. Organic: GOC 3",
    "",
    "📅 17/10/2026 (Sat)",
    "Physical Chemistry: Thermodynamics 4",
    "Inorganic Chemistry: P Block 2",
  ].join("\n");
  const plan = parsePlan(text);
  assert.equal(plan.lectures.length, 5);
  assert.ok(isVerified(plan));
  assert.equal(plan.lectures[0].date, "2026-10-15");
  assert.equal(plan.lectures[0].title, "Rotation 2");
  assert.equal(plan.lectures[0].subject, "Physics");
  assert.equal(plan.lectures[2].date, "2026-10-16");
  assert.equal(plan.lectures[3].date, "2026-10-17");
  assert.equal(plan.lectures[3].subject, "Physical Chem");
  assert.equal(plan.lectures[4].subject, "Inorganic");
  assert.equal(plan.lectures[4].order, 2);
  assert.deepEqual(plan.noLectureDates, []);
});

test("NO LECTURE on its own line or on the heading", () => {
  const text = [
    "## 2026-10-27",
    "Physics: Rotation 14",
    "## 2026-10-28 (Wed)",
    "NO LECTURE",
    "## 29 Oct 2026 — No lecture",
    "## 2026-10-30",
    "Maths: Relation & Function 8",
  ].join("\n");
  const plan = parsePlan(text);
  assert.equal(plan.lectures.length, 2);
  assert.deepEqual(plan.noLectureDates, ["2026-10-28", "2026-10-29"]);
});

test("lectures before any heading are counted but not placed, so verification fails", () => {
  const plan = parsePlan(["Physics: Orphan 1", "## 2026-10-15", "Physics: Centre of Mass 1"].join("\n"));
  assert.equal(plan.lectures.length, 1);
  assert.equal(plan.lectureLineCount, 2);
  assert.equal(isVerified(plan), false);
  assert.equal(plan.warnings.length, 1);
});

test("unknown subjects are reported and ignored", () => {
  const plan = parsePlan(["## 2026-10-15", "Biology: Cells 1", "Physics: Rotation 1"].join("\n"));
  assert.equal(plan.lectures.length, 1);
  assert.equal(plan.warnings.length, 1);
});

test("ids and dates", () => {
  assert.equal(planLectureId("2026-10-15", 1), "plan_2026-10-15_01");
  assert.equal(planLectureId("2026-10-15", 12), "plan_2026-10-15_12");
  assert.equal(safeDate(2026, 2, 30), null);
  assert.equal(safeDate(2028, 2, 29), "2028-02-29");
});

test("seed document matches the Android seeder", () => {
  const doc = planLectureDoc(loadPlan().lectures[0]);
  assert.equal(doc.completed, false);
  assert.equal(doc.completedAt, null);
  assert.equal(doc.plannedMinutes, 120);
  assert.equal(doc.studiedSeconds, 0);
  assert.equal(doc.source, "plan");
  assert.equal(doc.calendarEventId, null);
  assert.equal((doc.updatedAt as { toMillis(): number }).toMillis(), 0);
  assert.deepEqual(Object.keys(doc).sort(), [
    "calendarEventId", "completed", "completedAt", "date", "order", "plannedMinutes", "source", "studiedSeconds", "subject", "title", "updatedAt",
  ]);
});
