#!/usr/bin/env node
/**
 * Converts the original "Lecture Tracker.html" prototype (which stores the plan as a
 * compact `RAW` block such as `10-15:CM1 CM2 IU1 ...` plus a chapter dictionary `CH`)
 * into the canonical, human-readable plan file used by the Android app and the
 * Cloud Functions seeder:
 *
 *   ## 2026-10-15 (Thu)
 *   Physics: Centre of Mass 1
 *   ...
 *   ## 2026-10-28 (Wed)
 *   NO LECTURE
 *
 * Usage (from the repo root):
 *   node tools/convert_html_plan.mjs "docs/source/Lecture Tracker.html" app/src/main/assets/lecture_plan.md
 *
 * The script prints the total number of lectures so you can cross-check the seeders.
 */
import { readFileSync, writeFileSync } from "node:fs";

const [, , inputPath = "docs/source/Lecture Tracker.html", outputPath = "app/src/main/assets/lecture_plan.md"] =
  process.argv;

const html = readFileSync(inputPath, "utf8");

const chMatch = html.match(/const CH=\{([\s\S]*?)\};/);
const rawMatch = html.match(/const RAW=`([\s\S]*?)`;/);
if (!chMatch || !rawMatch) {
  console.error("Could not find the CH dictionary or RAW plan block in", inputPath);
  process.exit(1);
}

/** code -> [subject, chapter] */
const chapters = new Map();
for (const m of chMatch[1].matchAll(/([A-Z]{2}):\['([^']+)','([^']+)'\]/g)) {
  chapters.set(m[1], [m[2], m[3]]);
}

const WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const pad = (n) => String(n).padStart(2, "0");
const iso = (d) => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;

/** isoDate -> ["Physics: Centre of Mass 1", ...] */
const byDate = new Map();
let total = 0;
for (const line of rawMatch[1].trim().split("\n")) {
  const [mmdd, codes] = line.trim().split(":");
  const month = Number(mmdd.slice(0, 2));
  // The plan runs Oct 2026 -> Jan 2027: months >= 10 belong to 2026, the rest to 2027.
  const year = month >= 10 ? 2026 : 2027;
  const key = `${year}-${mmdd}`;
  const entries = byDate.get(key) ?? [];
  for (const token of codes.trim().split(/\s+/)) {
    const m = token.match(/^([A-Z]{2})(\d+)$/);
    if (!m || !chapters.has(m[1])) {
      console.error(`Unknown lecture code "${token}" on ${key}`);
      process.exit(1);
    }
    const [subject, chapter] = chapters.get(m[1]);
    entries.push(`${subject}: ${chapter} ${m[2]}`);
    total++;
  }
  byDate.set(key, entries);
}

const keys = [...byDate.keys()].sort();
const first = new Date(`${keys[0]}T00:00:00Z`);
const last = new Date(`${keys[keys.length - 1]}T00:00:00Z`);

const out = [];
out.push("# Lecture Plan — 15 Oct 2026 to 9 Jan 2027");
out.push("");
out.push("Format: one date heading per day, then one `Subject: Title N` line per lecture.");
out.push("Days without lectures contain the single line NO LECTURE.");
out.push("Subjects: Physics, Organic, Physical Chem, Inorganic, Maths.");
out.push(`Total lectures: ${total}`);
out.push("");

let noLectureDays = 0;
for (let d = new Date(first); d <= last; d.setUTCDate(d.getUTCDate() + 1)) {
  const key = iso(d);
  out.push(`## ${key} (${WEEKDAYS[d.getUTCDay()]})`);
  const entries = byDate.get(key);
  if (!entries || entries.length === 0) {
    out.push("NO LECTURE");
    noLectureDays++;
  } else {
    out.push(...entries);
  }
  out.push("");
}

writeFileSync(outputPath, out.join("\n"));
console.log(
  `Wrote ${outputPath}: ${total} lectures on ${byDate.size} days, ${noLectureDays} NO LECTURE days (${keys[0]} → ${keys[keys.length - 1]}).`,
);
