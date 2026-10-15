import { getFirestore, Timestamp } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { computeDiff, addDays, ExistingCalendarLecture, GoogleEvent, toEventInput } from "./calendar/diff";
import { DEFAULT_PLANNED_MINUTES, DEFAULT_TIME_ZONE, LECTURES, REGION, USERS } from "./config";
import { localTime } from "./reminderRules";

const SYNC_DAYS = 60;
const MAX_PAGES = 20;

interface SyncRequest {
  accessToken?: unknown;
  calendarId?: unknown;
  timeZone?: unknown;
  ignoredEventIds?: unknown;
}

async function fetchEvents(accessToken: string, calendarId: string, timeMin: string, timeMax: string): Promise<GoogleEvent[]> {
  const events: GoogleEvent[] = [];
  let pageToken: string | undefined;
  for (let page = 0; page < MAX_PAGES; page++) {
    const params = new URLSearchParams({
      timeMin,
      timeMax,
      singleEvents: "true",
      orderBy: "startTime",
      maxResults: "250",
      showDeleted: "false",
    });
    if (pageToken) params.set("pageToken", pageToken);
    const url = `https://www.googleapis.com/calendar/v3/calendars/${encodeURIComponent(calendarId)}/events?${params}`;
    const res = await fetch(url, { headers: { Authorization: `Bearer ${accessToken}` } });
    if (res.status === 401) throw new HttpsError("unauthenticated", "Google access token expired or revoked.");
    if (res.status === 403) throw new HttpsError("permission-denied", "No permission to read this calendar.");
    if (res.status === 404) throw new HttpsError("failed-precondition", "Calendar not found. Pick a calendar again.");
    if (!res.ok) throw new HttpsError("unavailable", `Google Calendar API error ${res.status}`);
    const body = (await res.json()) as { items?: GoogleEvent[]; nextPageToken?: string };
    events.push(...(body.items ?? []));
    pageToken = body.nextPageToken;
    if (!pageToken) break;
  }
  return events;
}

/**
 * Callable: imports the next 60 days of the chosen Google Calendar as lectures for the signed-in user.
 * The OAuth access token (scope calendar.readonly) comes from the app for this one call and is never stored.
 * Dedupe by calendarEventId (doc id cal_<eventId>), updates changed events, deletes vanished ones unless completed.
 */
export const syncCalendar = onCall({ region: REGION, timeoutSeconds: 120, memory: "256MiB" }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  const data = (request.data ?? {}) as SyncRequest;
  const accessToken = typeof data.accessToken === "string" ? data.accessToken : "";
  const calendarId = typeof data.calendarId === "string" ? data.calendarId : "";
  if (!accessToken || !calendarId) throw new HttpsError("invalid-argument", "accessToken and calendarId are required.");
  const timeZone = typeof data.timeZone === "string" && data.timeZone ? data.timeZone : DEFAULT_TIME_ZONE;
  const ignored = new Set(Array.isArray(data.ignoredEventIds) ? data.ignoredEventIds.filter((x): x is string => typeof x === "string").slice(0, 2000) : []);

  const nowMs = Date.now();
  const windowStart = localTime(nowMs, timeZone).date;
  const windowEnd = addDays(windowStart, SYNC_DAYS);
  // A slightly wider query; the diff filters by local date.
  const timeMin = new Date(nowMs - 36 * 3600 * 1000).toISOString();
  const timeMax = new Date(nowMs + (SYNC_DAYS + 1) * 24 * 3600 * 1000).toISOString();

  const googleEvents = await fetchEvents(accessToken, calendarId, timeMin, timeMax);
  const events = googleEvents
    .map((e) => toEventInput(e, timeZone))
    .filter((e): e is NonNullable<typeof e> => e !== null && !ignored.has(e.eventId));

  const db = getFirestore();
  const lecturesRef = db.collection(USERS).doc(uid).collection(LECTURES);
  const existingSnap = await lecturesRef.where("source", "==", "calendar").get();
  const existing: ExistingCalendarLecture[] = existingSnap.docs.map((d) => ({
    id: d.id,
    calendarEventId: String(d.get("calendarEventId") ?? ""),
    date: String(d.get("date") ?? ""),
    order: Number(d.get("order") ?? 0),
    subject: String(d.get("subject") ?? "Other"),
    title: String(d.get("title") ?? ""),
    plannedMinutes: Number(d.get("plannedMinutes") ?? DEFAULT_PLANNED_MINUTES),
    completed: d.get("completed") === true,
  }));

  const diff = computeDiff(events, existing, windowStart, windowEnd);
  const now = Timestamp.fromMillis(nowMs);
  const writer = db.bulkWriter();
  for (const f of diff.inserts) {
    writer.set(lecturesRef.doc(f.id), {
      date: f.date,
      order: f.order,
      subject: f.subject,
      title: f.title,
      completed: false,
      completedAt: null,
      plannedMinutes: f.plannedMinutes,
      studiedSeconds: 0,
      source: "calendar",
      calendarEventId: f.calendarEventId,
      updatedAt: now,
    });
  }
  for (const f of diff.updates) {
    // Completion state and studied time are preserved.
    writer.update(lecturesRef.doc(f.id), {
      date: f.date,
      order: f.order,
      subject: f.subject,
      title: f.title,
      plannedMinutes: f.plannedMinutes,
      updatedAt: now,
    });
  }
  for (const id of diff.deletes) writer.delete(lecturesRef.doc(id));
  await writer.close();

  const result = {
    inserted: diff.inserts.length,
    updated: diff.updates.length,
    deleted: diff.deletes.length,
    keptCompleted: diff.keptCompleted,
    events: events.length,
  };
  logger.info("syncCalendar", { uid, ...result });
  return result;
});
