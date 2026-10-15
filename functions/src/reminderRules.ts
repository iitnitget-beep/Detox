/**
 * TypeScript port of app/src/main/java/com/deeproot/app/core/reminder/ReminderRules.kt, used by scheduledReminder.
 * Local times are represented as { date: "yyyy-MM-dd", minute: minutes since local midnight } in the user's time zone.
 */

export interface ReminderSettings {
  enabled: boolean;
  intervalHours: number;
  startHour: number;
  endHour: number;
}

export interface LocalTime {
  date: string;
  minute: number;
}

/** Background work may run a little early; a run this close to the next slot counts for that slot. */
export const TOLERANCE_MINUTES = 15;

/** The server only pushes when the phone had this long after the slot to remind on its own. */
export const SERVER_GRACE_MINUTES = 30;

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, Math.trunc(v)));

export function sanitize(s: ReminderSettings): ReminderSettings {
  return {
    enabled: s.enabled,
    intervalHours: clamp(Number.isFinite(s.intervalHours) ? s.intervalHours : 2, 1, 12),
    startHour: clamp(Number.isFinite(s.startHour) ? s.startHour : 8, 0, 23),
    endHour: clamp(Number.isFinite(s.endHour) ? s.endHour : 22, 1, 24),
  };
}

/** Slot start minutes of a day: startHour, startHour + interval, … strictly before endHour. */
export function slotMinutes(settings: ReminderSettings): number[] {
  const s = sanitize(settings);
  const slots: number[] = [];
  for (let h = s.startHour; h < s.endHour; h += s.intervalHours) slots.push(h * 60);
  return slots;
}

export function isWithinWindow(t: LocalTime, settings: ReminderSettings): boolean {
  const s = sanitize(settings);
  if (s.startHour >= s.endHour) return false;
  return t.minute >= s.startHour * 60 && t.minute < s.endHour * 60;
}

/** The slot a check at `t` belongs to (looking ahead by the tolerance, within the same day). */
export function effectiveSlot(t: LocalTime, settings: ReminderSettings): number | null {
  const probe = Math.min(t.minute + TOLERANCE_MINUTES, 24 * 60 - 1);
  const slots = slotMinutes(settings).filter((m) => m <= probe);
  return slots.length > 0 ? slots[slots.length - 1] : null;
}

/** True if the last reminder (local or push) already covered the slot that `now` belongs to. */
export function wasRemindedThisSlot(now: LocalTime, last: LocalTime | null, settings: ReminderSettings): boolean {
  if (!last) return false;
  const slot = effectiveSlot(now, settings);
  if (slot === null) return false;
  if (last.date !== now.date) return last.date > now.date;
  return last.minute >= slot - TOLERANCE_MINUTES;
}

export type ServerDecision = { send: true; slot: number } | { send: false; reason: string };

/** Push only inside the window, after the grace period, and if neither the phone nor the server reminded this slot. */
export function serverDecision(now: LocalTime, settings: ReminderSettings, last: LocalTime | null): ServerDecision {
  const s = sanitize(settings);
  if (!s.enabled) return { send: false, reason: "disabled" };
  if (s.startHour >= s.endHour) return { send: false, reason: "invalid-window" };
  if (!isWithinWindow(now, s)) return { send: false, reason: "outside-hours" };
  const slot = effectiveSlot(now, s);
  if (slot === null) return { send: false, reason: "outside-hours" };
  if (now.minute < slot + SERVER_GRACE_MINUTES) return { send: false, reason: "grace-period" };
  if (wasRemindedThisSlot(now, last, s)) return { send: false, reason: "already-reminded" };
  return { send: true, slot };
}

/** "3 lectures pending. Next: Physics – Rotation 2" (same text as the app). */
export function buildMessage(count: number, next: { subject: string; title: string } | null): string | null {
  if (count <= 0 || !next) return null;
  return `${count} ${count === 1 ? "lecture" : "lectures"} pending. Next: ${next.subject} – ${next.title}`;
}

/** Local date and minute-of-day of an instant in an IANA time zone. */
export function localTime(epochMillis: number, timeZone: string): LocalTime {
  let parts: Record<string, string>;
  try {
    const fmt = new Intl.DateTimeFormat("en-CA", {
      timeZone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    });
    parts = Object.fromEntries(fmt.formatToParts(new Date(epochMillis)).map((p) => [p.type, p.value]));
  } catch {
    return localTime(epochMillis, "UTC");
  }
  return {
    date: `${parts.year}-${parts.month}-${parts.day}`,
    minute: (Number(parts.hour) % 24) * 60 + Number(parts.minute),
  };
}
