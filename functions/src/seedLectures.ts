import { getFirestore } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { REGION } from "./config";
import { seedPlanForUser } from "./seed";

/**
 * Callable: seeds the signed-in user's lectures from lecture_plan.md and verifies the Firestore count matches the file.
 * The app calls it once per account; `{ force: true }` re-runs the "create missing" pass.
 */
export const seedLectures = onCall({ region: REGION, timeoutSeconds: 120, memory: "256MiB" }, async (request) => {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  const force = (request.data as { force?: unknown } | undefined)?.force === true;
  try {
    const outcome = await seedPlanForUser(getFirestore(), uid, { force });
    logger.info("seedLectures", { uid, ...outcome });
    if (!outcome.verified) {
      logger.error("seedLectures verification failed", { uid, ...outcome });
    }
    return outcome;
  } catch (e) {
    logger.error("seedLectures failed", { uid, error: String(e) });
    throw new HttpsError("internal", "Seeding failed. Check the function logs.");
  }
});
