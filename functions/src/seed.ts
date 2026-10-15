import { Firestore, Timestamp } from "firebase-admin/firestore";
import { DEFAULT_PLANNED_MINUTES, LECTURES, USERS } from "./config";
import { loadPlan } from "./plan/loadPlan";
import { isVerified, PlannedLecture } from "./plan/parser";
import { recomputeSummary } from "./summary";

export interface SeedOutcome {
  /** Lectures in lecture_plan.md. */
  expected: number;
  /** Documents created by this call. */
  created: number;
  /** Plan documents that already existed (e.g. uploaded by the app). */
  alreadyPresent: number;
  /** Plan documents in Firestore after seeding. */
  totalPlanDocs: number;
  /** Every lecture of the plan file exists in Firestore (count matches the file). */
  verified: boolean;
  /** True when the user was already seeded and `force` was not set. */
  skipped: boolean;
}

/** Firestore document for a plan lecture. Identical to what the Android seeder writes (updatedAt = epoch 0). */
export function planLectureDoc(l: PlannedLecture): Record<string, unknown> {
  return {
    date: l.date,
    order: l.order,
    subject: l.subject,
    title: l.title,
    completed: false,
    completedAt: null,
    plannedMinutes: DEFAULT_PLANNED_MINUTES,
    studiedSeconds: 0,
    source: "plan",
    calendarEventId: null,
    // 0 = pristine seed: any real edit on a device wins the last-writer-wins merge.
    updatedAt: Timestamp.fromMillis(0),
  };
}

const ALREADY_EXISTS = 6;

/** The parts of @google-cloud/firestore's BulkWriterError we use (the class is not re-exported by firebase-admin). */
interface BulkWriteFailure {
  code: number;
  failedAttempts: number;
}

/**
 * Creates every missing plan lecture under users/{uid}/lectures (never overwrites existing documents, so progress is
 * safe), then verifies that all lectures of the plan file exist and marks the user as seeded.
 */
export async function seedPlanForUser(db: Firestore, uid: string, opts: { force?: boolean } = {}): Promise<SeedOutcome> {
  const plan = loadPlan();
  if (!isVerified(plan)) {
    throw new Error(
      `Plan file check failed: parsed ${plan.lectures.length} of ${plan.lectureLineCount} lecture lines. ` +
        plan.warnings.slice(0, 3).join("; "),
    );
  }
  const expected = plan.lectures.length;
  const userRef = db.collection(USERS).doc(uid);
  const lecturesRef = userRef.collection(LECTURES);

  const userSnap = await userRef.get();
  if (userSnap.exists && userSnap.get("planSeeded") === true && !opts.force) {
    const total = (await lecturesRef.where("source", "==", "plan").count().get()).data().count;
    return { expected, created: 0, alreadyPresent: total, totalPlanDocs: total, verified: true, skipped: true };
  }

  const existingIds = new Set((await lecturesRef.where("source", "==", "plan").select().get()).docs.map((d) => d.id));
  const missing = plan.lectures.filter((l) => !existingIds.has(l.id));

  let created = 0;
  const writer = db.bulkWriter();
  writer.onWriteError((error: BulkWriteFailure) => {
    if (error.code === ALREADY_EXISTS) return false; // created concurrently by the app — fine
    return error.failedAttempts < 5;
  });
  const writes = missing.map((l) =>
    writer.create(lecturesRef.doc(l.id), planLectureDoc(l)).then(
      () => {
        created++;
      },
      (error: BulkWriteFailure) => {
        if (error.code !== ALREADY_EXISTS) throw error;
      },
    ),
  );
  await writer.close();
  await Promise.all(writes);

  const afterIds = new Set((await lecturesRef.where("source", "==", "plan").select().get()).docs.map((d) => d.id));
  const verified = plan.lectures.every((l) => afterIds.has(l.id));
  if (verified) {
    // Only fields allowed by firestore.rules for users/{uid}, otherwise later client writes would be rejected.
    await userRef.set({ planSeeded: true, planSeededCount: expected }, { merge: true });
    // Seed creations are skipped by onLectureCompleted (no 479-event storm); one recount initialises the stats.
    await recomputeSummary(db, uid);
  }
  return {
    expected,
    created,
    alreadyPresent: existingIds.size,
    totalPlanDocs: afterIds.size,
    verified,
    skipped: false,
  };
}
