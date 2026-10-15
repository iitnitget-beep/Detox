import { DocumentReference, FieldValue, Firestore } from "firebase-admin/firestore";
import { DEFAULT_TIME_ZONE, LECTURES, STATS, STATS_SUMMARY_DOC, USERS } from "./config";
import { LectureLike, Summary, summaryFromLectures } from "./stats";

export async function userTimeZone(userRef: DocumentReference): Promise<string> {
  const snap = await userRef.get();
  const tz = snap.get("timeZone");
  return typeof tz === "string" && tz.length > 0 ? tz : DEFAULT_TIME_ZONE;
}

/** Rebuilds users/{uid}/stats/summary from all lectures (once per user, and after seeding). */
export async function recomputeSummary(db: Firestore, uid: string, timeZone?: string): Promise<Summary> {
  const userRef = db.collection(USERS).doc(uid);
  const tz = timeZone ?? (await userTimeZone(userRef));
  const snapshot = await userRef.collection(LECTURES).get();
  const summary = summaryFromLectures(snapshot.docs.map((d) => d.data() as LectureLike), tz);
  await userRef.collection(STATS).doc(STATS_SUMMARY_DOC).set({
    ...summary,
    initialized: true,
    // Writes committed up to this instant are already counted; older trigger events must not be applied again.
    initializedAt: snapshot.readTime,
    updatedAt: FieldValue.serverTimestamp(),
  });
  return summary;
}
