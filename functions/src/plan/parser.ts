/**
 * TypeScript port of app/src/main/java/com/deeproot/app/core/plan/PlanParser.kt.
 * Both parsers must produce identical lectures and document ids (see test/plan.test.ts).
 *
 * Accepted structure (Markdown or plain text):
 *   ## 2026-10-15 (Thu)          <- date heading (ISO, "15 Oct 2026", "Oct 15, 2026", "15/10/2026", ...)
 *   Physics: Centre of Mass 1    <- "Subject: Title N"
 *   - **Maths:** Matrix 2        <- bullets / bold are tolerated
 *   ## 2026-10-28 (Wed)
 *   NO LECTURE                   <- the date has no lectures (may also appear on the heading line)
 * Lines before the first heading are ignored.
 */

export type SubjectLabel = "Physics" | "Organic" | "Physical Chem" | "Inorganic" | "Maths" | "Other";

export const SUBJECT_LABELS: readonly SubjectLabel[] = ["Physics", "Organic", "Physical Chem", "Inorganic", "Maths", "Other"];

const SUBJECT_ALIASES: Record<string, SubjectLabel> = {
  physics: "Physics",
  organic: "Organic",
  "organic chem": "Organic",
  "organic chemistry": "Organic",
  "physical chem": "Physical Chem",
  "physical chemistry": "Physical Chem",
  inorganic: "Inorganic",
  "inorganic chem": "Inorganic",
  "inorganic chemistry": "Inorganic",
  maths: "Maths",
  math: "Maths",
  mathematics: "Maths",
  other: "Other",
};

export function parseSubject(text: string): SubjectLabel | null {
  return SUBJECT_ALIASES[text.trim().toLowerCase().replace(/\s+/g, " ")] ?? null;
}

export interface PlannedLecture {
  id: string;
  /** ISO yyyy-MM-dd */
  date: string;
  /** 1-based position within the day */
  order: number;
  subject: SubjectLabel;
  title: string;
}

export interface ParsedPlan {
  lectures: PlannedLecture[];
  noLectureDates: string[];
  lectureLineCount: number;
  warnings: string[];
}

/** `plan_2026-10-15_01` — must match PlanIds.planLectureId in Kotlin. */
export function planLectureId(date: string, order: number): string {
  return `plan_${date}_${String(order).padStart(2, "0")}`;
}

/** Every "Subject: Title" line was placed under a date. */
export function isVerified(plan: ParsedPlan): boolean {
  return plan.lectures.length === plan.lectureLineCount && plan.lectureLineCount > 0;
}

const LECTURE_LINE =
  /^(Physics|Organic(?:\s+Chem(?:istry)?)?|Physical\s+Chem(?:istry)?|Inorganic(?:\s+Chem(?:istry)?)?|Maths?|Mathematics)\s*:\s*(.+)$/i;
const UNKNOWN_SUBJECT_LINE = /^([A-Za-z][A-Za-z ]{1,30})\s*:\s*\S.*$/;
const NO_LECTURE_TEST = /\bno\s+lectures?\b/i;
const NO_LECTURE_ALL = /\bno\s+lectures?\b/gi;

const ISO_DATE = /(?<!\d)(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)/;
const DAY_MONTH_YEAR = /(?<!\d)(\d{1,2})(?:st|nd|rd|th)?[\s-]+([A-Za-z]{3,9})\.?,?[\s-]+(\d{4})(?!\d)/;
const MONTH_DAY_YEAR = /(?<![A-Za-z])([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})(?!\d)/;
const NUMERIC_DMY = /(?<!\d)(\d{1,2})[/.](\d{1,2})[/.](\d{4})(?!\d)/;

const WEEKDAY_ALL =
  /\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\b/gi;
const DAY_NUMBER_ALL = /\bday\s*\d+\b/gi;
const NON_ALNUM_ALL = /[^\p{L}\p{N}]+/gu;
const LEADING_MARKUP = /^\s*(?:#{1,6}\s*|[-*+•]\s+|\d+[.)]\s+)?/;
const INLINE_MARKUP_ALL = /\*\*|__|`/g;
const WHITESPACE_ALL = /\s+/g;

const MONTHS: Record<string, number> = {
  jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12,
};

export function parsePlan(text: string): ParsedPlan {
  const lectures: PlannedLecture[] = [];
  const noLecture = new Set<string>();
  const warnings: string[] = [];
  const nextOrder = new Map<string, number>();
  let currentDate: string | null = null;
  let lectureLines = 0;

  text.split(/\r\n|\r|\n/).forEach((rawLine, index) => {
    const lineNo = index + 1;
    const line = clean(rawLine);
    if (line.length === 0) return;

    const lectureMatch = LECTURE_LINE.exec(line);
    if (lectureMatch) {
      lectureLines++;
      if (currentDate === null) {
        warnings.push(`Line ${lineNo}: lecture before any date heading was skipped: "${line}"`);
        return;
      }
      const subject = parseSubject(lectureMatch[1]);
      const title = lectureMatch[2].trim();
      if (subject === null || title.length === 0) {
        warnings.push(`Line ${lineNo}: could not read lecture "${line}"`);
        return;
      }
      const order = (nextOrder.get(currentDate) ?? 0) + 1;
      nextOrder.set(currentDate, order);
      lectures.push({ id: planLectureId(currentDate, order), date: currentDate, order, subject, title });
      return;
    }

    const heading = parseHeading(line);
    if (heading) {
      currentDate = heading.date;
      if (heading.noLecture) noLecture.add(heading.date);
      return;
    }

    if (currentDate === null) return; // preamble before the first heading
    if (NO_LECTURE_TEST.test(line)) {
      noLecture.add(currentDate);
    } else if (UNKNOWN_SUBJECT_LINE.test(line)) {
      warnings.push(`Line ${lineNo}: unknown subject, ignored: "${line}"`);
    }
  });

  const datesWithLectures = new Set(lectures.map((l) => l.date));
  for (const date of noLecture) {
    if (datesWithLectures.has(date)) {
      warnings.push(`${date} is marked NO LECTURE but also lists lectures; the lectures were kept.`);
    }
  }

  lectures.sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : a.order - b.order));
  return {
    lectures,
    noLectureDates: [...noLecture].filter((d) => !datesWithLectures.has(d)).sort(),
    lectureLineCount: lectureLines,
    warnings,
  };
}

/** Strips Markdown decoration so "- **Physics:** Rotation 2" becomes "Physics: Rotation 2". */
function clean(raw: string): string {
  return raw.replace(INLINE_MARKUP_ALL, "").replace(LEADING_MARKUP, "").replace(WHITESPACE_ALL, " ").trim();
}

/**
 * A heading is a line that, after removing the date, weekday names, "Day N" and an optional "NO LECTURE" marker,
 * contains nothing meaningful. This keeps titles like "Lecture Plan — 15 Oct 2026 to 9 Jan 2027" from being headings.
 */
function parseHeading(line: string): { date: string; noLecture: boolean } | null {
  const found = findDate(line);
  if (!found) return null;
  const rest = (line.slice(0, found.start) + line.slice(found.end))
    .replace(WEEKDAY_ALL, " ")
    .replace(DAY_NUMBER_ALL, " ")
    .replace(NO_LECTURE_ALL, " ")
    .replace(NON_ALNUM_ALL, "");
  if (rest.length > 0) return null;
  return { date: found.date, noLecture: NO_LECTURE_TEST.test(line) };
}

function findDate(line: string): { date: string; start: number; end: number } | null {
  const range = (m: RegExpExecArray) => ({ start: m.index, end: m.index + m[0].length });

  const iso = ISO_DATE.exec(line);
  if (iso) {
    const date = safeDate(+iso[1], +iso[2], +iso[3]);
    if (date) return { date, ...range(iso) };
  }
  const dmy = DAY_MONTH_YEAR.exec(line);
  if (dmy) {
    const month = monthOf(dmy[2]);
    const date = month ? safeDate(+dmy[3], month, +dmy[1]) : null;
    if (date) return { date, ...range(dmy) };
  }
  const mdy = MONTH_DAY_YEAR.exec(line);
  if (mdy) {
    const month = monthOf(mdy[1]);
    const date = month ? safeDate(+mdy[3], month, +mdy[2]) : null;
    if (date) return { date, ...range(mdy) };
  }
  const num = NUMERIC_DMY.exec(line);
  if (num) {
    const date = safeDate(+num[3], +num[2], +num[1]);
    if (date) return { date, ...range(num) };
  }
  return null;
}

function monthOf(name: string): number | undefined {
  return MONTHS[name.slice(0, 3).toLowerCase()];
}

/** Returns ISO yyyy-MM-dd for a valid calendar date, otherwise null. */
export function safeDate(year: number, month: number, day: number): string | null {
  if (!Number.isInteger(year) || !Number.isInteger(month) || !Number.isInteger(day)) return null;
  if (month < 1 || month > 12 || day < 1 || day > 31 || year < 1 || year > 9999) return null;
  const d = new Date(Date.UTC(year, month - 1, day));
  d.setUTCFullYear(year); // Date.UTC maps years 0..99 to 1900..1999
  if (d.getUTCFullYear() !== year || d.getUTCMonth() !== month - 1 || d.getUTCDate() !== day) return null;
  return `${String(year).padStart(4, "0")}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}
