/**
 * Port of app/src/main/java/com/deeproot/app/core/calendar/CalendarDiff.kt plus Google Calendar event conversion.
 */
import { localTime } from "../reminderRules";
import { guessSubject } from "./subject";

export const ORDER_BASE = 1000;
export const MAX_TITLE_LENGTH = 200;
export const MIN_PLANNED_MINUTES = 15;
export const MAX_PLANNED_MINUTES = 720;
export const DEFAULT_PLANNED_MINUTES = 120;

export interface CalendarEventInput {
  eventId: string;
  title: string;
  /** yyyy-MM-dd in the user's time zone */
  date: string;
  startMinuteOfDay: number | null;
  durationMinutes: number | null;
  cancelled: boolean;
}

export interface CalendarLectureFields {
  id: string;
  calendarEventId: string;
  date: string;
  order: number;
  subject: string;
  title: string;
  plannedMinutes: number;
}

export interface ExistingCalendarLecture extends CalendarLectureFields {
  completed: boolean;
}

export interface CalendarDiffResult {
  inserts: CalendarLectureFields[];
  updates: CalendarLectureFields[];
  deletes: string[];
  keptCompleted: number;
}

/** Google Calendar API v3 event (only the fields we use). */
export interface GoogleEvent {
  id?: string;
  status?: string;
  summary?: string;
  start?: { date?: string; dateTime?: string };
  end?: { date?: string; dateTime?: string };
}

export function lectureIdFor(eventId: string): string {
  return "cal_" + eventId.replace(/[^A-Za-z0-9_-]/g, "_");
}

export function toFields(event: CalendarEventInput, defaultPlannedMinutes = DEFAULT_PLANNED_MINUTES): CalendarLectureFields {
  const trimmed = event.title.trim();
  const title = (trimmed.length > 0 ? trimmed : "(Untitled event)").slice(0, MAX_TITLE_LENGTH);
  const planned =
    event.durationMinutes !== null && event.durationMinutes > 0
      ? Math.min(MAX_PLANNED_MINUTES, Math.max(MIN_PLANNED_MINUTES, event.durationMinutes))
      : defaultPlannedMinutes;
  const minute = Math.min(24 * 60 - 1, Math.max(0, event.startMinuteOfDay ?? 0));
  return {
    id: lectureIdFor(event.eventId),
    calendarEventId: event.eventId,
    date: event.date,
    order: ORDER_BASE + minute,
    subject: guessSubject(title),
    title,
    plannedMinutes: planned,
  };
}

const byDateThenOrder = (a: CalendarLectureFields, b: CalendarLectureFields) =>
  a.date < b.date ? -1 : a.date > b.date ? 1 : a.order - b.order;

export function computeDiff(
  events: CalendarEventInput[],
  existing: ExistingCalendarLecture[],
  windowStart: string,
  windowEndExclusive: string,
  defaultPlannedMinutes = DEFAULT_PLANNED_MINUTES,
): CalendarDiffResult {
  const inWindow = (date: string) => date >= windowStart && date < windowEndExclusive;
  const desired = new Map<string, CalendarLectureFields>();
  for (const e of events) {
    if (!e.cancelled && inWindow(e.date)) desired.set(e.eventId, toFields(e, defaultPlannedMinutes));
  }
  const existingByEvent = new Map(existing.map((x) => [x.calendarEventId, x]));
  const inserts = [...desired.values()].filter((f) => !existingByEvent.has(f.calendarEventId));
  const updates: CalendarLectureFields[] = [];
  for (const wanted of desired.values()) {
    const current = existingByEvent.get(wanted.calendarEventId);
    if (!current) continue;
    const changed =
      current.date !== wanted.date ||
      current.order !== wanted.order ||
      current.subject !== wanted.subject ||
      current.title !== wanted.title ||
      current.plannedMinutes !== wanted.plannedMinutes;
    if (changed) updates.push({ ...wanted, id: current.id });
  }
  const vanished = existing.filter((x) => !desired.has(x.calendarEventId) && inWindow(x.date));
  return {
    inserts: inserts.sort(byDateThenOrder),
    updates: updates.sort(byDateThenOrder),
    deletes: vanished.filter((x) => !x.completed).map((x) => x.id),
    keptCompleted: vanished.filter((x) => x.completed).length,
  };
}

/** Converts a Google event to the user's local calendar (all-day events keep their date and have no time). */
export function toEventInput(event: GoogleEvent, timeZone: string): CalendarEventInput | null {
  if (!event.id) return null;
  const cancelled = event.status === "cancelled";
  const title = event.summary ?? "";
  if (event.start?.date) {
    return { eventId: event.id, title, date: event.start.date, startMinuteOfDay: null, durationMinutes: null, cancelled };
  }
  const startMs = event.start?.dateTime ? Date.parse(event.start.dateTime) : NaN;
  if (!Number.isFinite(startMs)) {
    return cancelled ? { eventId: event.id, title, date: "0000-00-00", startMinuteOfDay: null, durationMinutes: null, cancelled } : null;
  }
  const endMs = event.end?.dateTime ? Date.parse(event.end.dateTime) : NaN;
  const local = localTime(startMs, timeZone);
  return {
    eventId: event.id,
    title,
    date: local.date,
    startMinuteOfDay: local.minute,
    durationMinutes: Number.isFinite(endMs) ? Math.round((endMs - startMs) / 60000) : null,
    cancelled,
  };
}

export function addDays(isoDate: string, days: number): string {
  const [y, m, d] = isoDate.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}
