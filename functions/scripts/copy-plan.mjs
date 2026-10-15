// Copies the single source of truth for the lecture plan (the Android asset) into the functions package,
// so seedLectures deploys with exactly the same file the app seeds from.
import { copyFileSync, mkdirSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const source = join(here, "..", "..", "app", "src", "main", "assets", "lecture_plan.md");
const targetDir = join(here, "..", "plan");
const target = join(targetDir, "lecture_plan.md");

if (!existsSync(source)) {
  if (existsSync(target)) {
    console.log(`copy-plan: ${source} not found, keeping existing ${target}`);
    process.exit(0);
  }
  console.error(`copy-plan: ${source} not found`);
  process.exit(1);
}
mkdirSync(targetDir, { recursive: true });
copyFileSync(source, target);
console.log(`copy-plan: ${source} -> ${target}`);
