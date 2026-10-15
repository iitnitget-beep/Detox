import { DocumentReference, FieldValue, getFirestore, Timestamp } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import * as logger from "firebase-functions/logger";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { DEFAULT_TIME_ZONE, LECTURES, REGION, SETTINGS, SETTINGS_DOC } from "./config";
import { buildMessage, localTime, sanitize, serverDecision } from "./reminderRules";

function millis(v: unknown): number {
  return v instanceof Timestamp ? v.toMillis() : 0;
}

/** Sends one fallback reminder to one user if the rules say so. Returns a short outcome for logging. */
export async function remindUser(userRef: DocumentReference, settings: FirebaseFirestore.DocumentData, nowMs: number): Promise<string> {
  const user = await userRef.get();
  const token = user.get("fcmToken");
  if (typeof token !== "string" || token.length === 0) return "no-token";

  const tzCandidate = settings.timeZone ?? user.get("timeZone");
  const timeZone = typeof tzCandidate === "string" && tzCandidate.length > 0 ? tzCandidate : DEFAULT_TIME_ZONE;
  const rules = sanitize({
    enabled: settings.remindersEnabled !== false,
    intervalHours: Number(settings.reminderIntervalHours ?? 2),
    startHour: Number(settings.reminderStartHour ?? 8),
    endHour: Number(settings.reminderEndHour ?? 22),
  });
  const now = localTime(nowMs, timeZone);
  const lastMs = Math.max(millis(user.get("lastLocalReminderAt")), millis(user.get("lastPushReminderAt")));
  const decision = serverDecision(now, rules, lastMs > 0 ? localTime(lastMs, timeZone) : null);
  if (!decision.send) return decision.reason;

  const lectures = userRef.collection(LECTURES);
  const pending = (await lectures.where("completed", "==", false).where("date", "<=", now.date).count().get()).data().count;
  if (pending === 0) return "nothing-pending";
  let next = (await lectures.where("completed", "==", false).where("date", "==", now.date).orderBy("order").limit(1).get()).docs[0];
  if (!next) {
    next = (
      await lectures.where("completed", "==", false).where("date", "<", now.date).orderBy("date").orderBy("order").limit(1).get()
    ).docs[0];
  }
  const message = buildMessage(pending, next ? { subject: String(next.get("subject")), title: String(next.get("title")) } : null);
  if (!message) return "nothing-pending";

  try {
    // Data-only + high priority: wakes the app even in Doze; the app re-checks with local data and shows the
    // notification itself, so a reminder already shown by the phone is never duplicated.
    await getMessaging().send({
      token,
      data: { type: "reminder", count: String(pending), message },
      android: { priority: "high", ttl: 60 * 60 * 1000 },
    });
  } catch (e) {
    const code = (e as { code?: string }).code ?? "";
    if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") {
      await userRef.update({ fcmToken: FieldValue.delete() });
      return "stale-token-removed";
    }
    throw e;
  }
  await userRef.set({ lastPushReminderAt: Timestamp.fromMillis(nowMs) }, { merge: true });
  return "sent";
}

/**
 * Every 30 minutes: server-side fallback for users whose phone did not show the reminder for the current slot
 * (Doze, battery saver, OEM task killers). Users opt in implicitly by keeping reminders enabled in the app.
 */
export const scheduledReminder = onSchedule(
  { schedule: "every 30 minutes", timeZone: "Etc/UTC", region: REGION, timeoutSeconds: 300, memory: "256MiB" },
  async () => {
    const db = getFirestore();
    const settingsDocs = await db.collectionGroup(SETTINGS).where("remindersEnabled", "==", true).get();
    const nowMs = Date.now();
    const outcomes: Record<string, number> = {};
    const docs = settingsDocs.docs.filter((d) => d.id === SETTINGS_DOC && d.ref.parent.parent !== null);
    for (let i = 0; i < docs.length; i += 20) {
      await Promise.all(
        docs.slice(i, i + 20).map(async (doc) => {
          const userRef = doc.ref.parent.parent as DocumentReference;
          try {
            const outcome = await remindUser(userRef, doc.data(), nowMs);
            outcomes[outcome] = (outcomes[outcome] ?? 0) + 1;
          } catch (e) {
            outcomes.error = (outcomes.error ?? 0) + 1;
            logger.error("scheduledReminder failed for user", { uid: userRef.id, error: String(e) });
          }
        }),
      );
    }
    logger.info("scheduledReminder", { users: docs.length, ...outcomes });
  },
);
