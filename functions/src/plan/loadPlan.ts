import { readFileSync } from "node:fs";
import * as path from "node:path";
import { ParsedPlan, parsePlan } from "./parser";

/**
 * `functions/plan/lecture_plan.md`, copied from the Android asset by `npm run copy-plan` (runs before build/test).
 * Resolves the same way from `src/plan` (tests) and `lib/plan` (deployed code).
 */
export const PLAN_FILE = path.join(__dirname, "..", "..", "plan", "lecture_plan.md");

let cached: ParsedPlan | undefined;

export function loadPlan(): ParsedPlan {
  if (!cached) cached = parsePlan(readFileSync(PLAN_FILE, "utf8"));
  return cached;
}
