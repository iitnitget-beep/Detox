/**
 * One-off CLI seeder (alternative to the callable):
 *
 *   cd functions
 *   gcloud auth application-default login        # or: export GOOGLE_APPLICATION_CREDENTIALS=/path/key.json
 *   npm run seed:user -- <uid> [--force] [--project deeproot-e4d17]
 *
 * The uid is shown in the app under Settings → Account & sync (or Firebase Console → Authentication).
 */
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { readFileSync } from "node:fs";
import * as path from "node:path";
import { seedPlanForUser } from "../seed";

function defaultProject(): string | undefined {
  try {
    const rc = JSON.parse(readFileSync(path.join(__dirname, "..", "..", "..", ".firebaserc"), "utf8"));
    return rc?.projects?.default;
  } catch {
    return undefined;
  }
}

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  const uid = args.find((a) => !a.startsWith("--") && args[args.indexOf(a) - 1] !== "--project");
  const projectIndex = args.indexOf("--project");
  const projectId = projectIndex >= 0 ? args[projectIndex + 1] : process.env.GCLOUD_PROJECT ?? defaultProject();
  if (!uid) {
    console.error("Usage: npm run seed:user -- <uid> [--force] [--project <projectId>]");
    process.exit(2);
  }
  initializeApp({ projectId });
  const outcome = await seedPlanForUser(getFirestore(), uid, { force: args.includes("--force") });
  console.log(JSON.stringify({ projectId, uid, ...outcome }, null, 2));
  if (!outcome.verified) {
    console.error(`Verification FAILED: ${outcome.totalPlanDocs} plan lectures in Firestore, expected ${outcome.expected}.`);
    process.exit(1);
  }
  console.log(`Verified: all ${outcome.expected} lectures from lecture_plan.md exist in Firestore.`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
