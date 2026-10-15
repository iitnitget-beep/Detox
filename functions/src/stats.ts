/**
 * Pure stats logic for onLectureCompleted (unit tested in test/stats.test.ts).
 *
 * users/{uid}/stats/summary:
 *   totalLectures, completedLectures, studiedSeconds,
 *   subjects: { [label]: { total, completed, studiedSeconds } },
 *   daily:    { [yyyy-MM-dd in the user's time zone]: { completed } },
 *   initialized: true, initializedAt: Timestamp (read time of the last full recount)
 */

export interface TimestampLike {
  toMillis(): number;
}

export interface LectureLike {
  subject?: unknown;
  completed?: unknown;
  completedAt?: TimestampLike | null;
  studiedSeconds?: unknown;
  updatedAt?: TimestampLike | null;
}

export interface SubjectTotals {
  total: number;
  completed: number;
  studiedSeconds: number;
}

export interface Summary {
  totalLectures: number;
  completedLectures: number;
  studiedSeconds: number;
  subjects: Record<string, SubjectTotals>;
  daily: Record<string, { completed: number }>;
}

function millis(t: TimestampLike | null | undefined): number | null {
  return t && typeof t.toMillis === "function" ? t.toMillis() : null;
}

function num(v: unknown): number {
  return typeof v === "number" && Number.isFinite(v) ? v : 0;
}

/** yyyy-MM-dd of an instant in an IANA time zone. */
export function localDate(epochMillis: number, timeZone: string): string {
  try {
    return new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(epochMillis));
  } catch {
    return new Date(epochMillis).toISOString().slice(0, 10);
  }
}

/**
 * The 479 plan documents are created with updatedAt = 0 and completed = false. Counting each of those creations would
 * mean 479 transactions on one summary document; instead they are covered by a single full recount.
 */
export function isPristineSeedCreate(before: LectureLike | null, after: LectureLike | null): boolean {
  return before === null && after !== null && (millis(after.updatedAt) ?? 0) === 0 && after.completed !== true;
}

function emptySummary(): Summary {
  return { totalLectures: 0, completedLectures: 0, studiedSeconds: 0, subjects: {}, daily: {} };
}

/** Adds (sign = 1) or removes (sign = -1) one lecture's contribution. */
function apply(summary: Summary, doc: LectureLike, sign: 1 | -1, timeZone: string): void {
  const subject = typeof doc.subject === "string" && doc.subject.length > 0 ? doc.subject : "Other";
  const completed = doc.completed === true;
  const studied = num(doc.studiedSeconds);
  summary.totalLectures += sign;
  summary.completedLectures += completed ? sign : 0;
  summary.studiedSeconds += studied * sign;
  const s = (summary.subjects[subject] ??= { total: 0, completed: 0, studiedSeconds: 0 });
  s.total += sign;
  s.completed += completed ? sign : 0;
  s.studiedSeconds += studied * sign;
  const completedAt = millis(doc.completedAt ?? null);
  if (completed && completedAt !== null) {
    const day = localDate(completedAt, timeZone);
    const d = (summary.daily[day] ??= { completed: 0 });
    d.completed += sign;
  }
}

function prune(summary: Summary): void {
  for (const [key, value] of Object.entries(summary.subjects)) {
    if (value.total === 0 && value.completed === 0 && value.studiedSeconds === 0) delete summary.subjects[key];
  }
  for (const [key, value] of Object.entries(summary.daily)) {
    if (value.completed === 0) delete summary.daily[key];
  }
}

/** Difference caused by one document write (create, update or delete). Null when nothing changes. */
export function statsDelta(before: LectureLike | null, after: LectureLike | null, timeZone: string): Summary | null {
  const delta = emptySummary();
  if (before) apply(delta, before, -1, timeZone);
  if (after) apply(delta, after, 1, timeZone);
  prune(delta);
  const unchanged =
    delta.totalLectures === 0 &&
    delta.completedLectures === 0 &&
    delta.studiedSeconds === 0 &&
    Object.keys(delta.subjects).length === 0 &&
    Object.keys(delta.daily).length === 0;
  return unchanged ? null : delta;
}

/** Full recount from every lecture document. */
export function summaryFromLectures(docs: LectureLike[], timeZone: string): Summary {
  const summary = emptySummary();
  for (const doc of docs) apply(summary, doc, 1, timeZone);
  prune(summary);
  return summary;
}
