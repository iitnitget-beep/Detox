import { FieldValue, getFirestore, Timestamp } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { REGION, STATS, STATS_SUMMARY_DOC, USERS } from "./config";
import { isPristineSeedCreate, LectureLike, statsDelta, Summary } from "./stats";
import { recomputeSummary, userTimeZone } from "./summary";

const DAY_MS = 24 * 60 * 60 * 1000;

/** Nested set-merge payload with FieldValue.increment for every non-zero value of the delta. */
function incrementPayload(delta: Summary): Record<string, unknown> {
  const inc = (n: number) => FieldValue.increment(n);
  const payload: Record<string, unknown> = { updatedAt: FieldValue.serverTimestamp() };
  if (delta.totalLectures) payload.totalLectures = inc(delta.totalLectures);
  if (delta.completedLectures) payload.completedLectures = inc(delta.completedLectures);
  if (delta.studiedSeconds) payload.studiedSeconds = inc(delta.studiedSeconds);
  const subjects: Record<string, Record<string, unknown>> = {};
  for (const [label, s] of Object.entries(delta.subjects)) {
    const entry: Record<string, unknown> = {};
    if (s.total) entry.total = inc(s.total);
    if (s.completed) entry.completed = inc(s.completed);
    if (s.studiedSeconds) entry.studiedSeconds = inc(s.studiedSeconds);
    if (Object.keys(entry).length > 0) subjects[label] = entry;
  }
  if (Object.keys(subjects).length > 0) payload.subjects = subjects;
  const daily: Record<string, Record<string, unknown>> = {};
  for (const [day, d] of Object.entries(delta.daily)) {
    if (d.completed) daily[day] = { completed: inc(d.completed) };
  }
  if (Object.keys(daily).length > 0) payload.daily = daily;
  return payload;
}

/**
 * Keeps users/{uid}/stats/summary up to date whenever a lecture is completed, un-completed, studied, created or
 * deleted. Idempotent: each event id is recorded in users/{uid}/statsEvents (TTL-deleted after 7 days), so retries
 * never double count.
 */
export const onLectureCompleted = onDocumentWritten(
  { document: "users/{uid}/lectures/{lectureId}", region: REGION, retry: true, memory: "256MiB" },
  async (event) => {
    const uid = event.params.uid;
    const before = event.data?.before.exists ? (event.data.before.data() as LectureLike) : null;
    const after = event.data?.after.exists ? (event.data.after.data() as LectureLike) : null;
    if (isPristineSeedCreate(before, after)) return;

    const db = getFirestore();
    const userRef = db.collection(USERS).doc(uid);
    const summaryRef = userRef.collection(STATS).doc(STATS_SUMMARY_DOC);
    const markerRef = userRef.collection("statsEvents").doc(event.id);
    const expireAt = Timestamp.fromMillis(Date.now() + 7 * DAY_MS);
    const timeZone = await userTimeZone(userRef);

    const summarySnap = await summaryRef.get();
    if (!summarySnap.exists || summarySnap.get("initialized") !== true) {
      await recomputeSummary(db, uid, timeZone); // includes this write
      await markerRef.set({ expireAt });
      logger.info("onLectureCompleted: summary initialised", { uid });
      return;
    }

    const initializedAt = summarySnap.get("initializedAt") as Timestamp | undefined;
    const eventTime = Date.parse(event.time);
    if (initializedAt && Number.isFinite(eventTime) && eventTime <= initializedAt.toMillis()) {
      return; // already part of the last full recount
    }

    const delta = statsDelta(before, after, timeZone);
    if (!delta) return;

    await db.runTransaction(async (tx) => {
      const marker = await tx.get(markerRef);
      if (marker.exists) return;
      tx.set(markerRef, { expireAt });
      tx.set(summaryRef, incrementPayload(delta), { merge: true });
    });
  },
);
