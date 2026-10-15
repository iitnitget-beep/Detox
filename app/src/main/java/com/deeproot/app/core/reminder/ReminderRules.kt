package com.deeproot.app.core.reminder

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Reminder preferences. Hours are 24h clock; the window is [startHour, endHour). */
data class ReminderSettings(
    val enabled: Boolean = true,
    val intervalHours: Int = 2,
    val startHour: Int = 8,
    val endHour: Int = 22,
) {
    /** Values clamped to the ranges the UI and the Firestore rules allow. */
    val sanitized: ReminderSettings
        get() = copy(
            intervalHours = intervalHours.coerceIn(1, 12),
            startHour = startHour.coerceIn(0, 23),
            endHour = endHour.coerceIn(1, 24),
        )

    val hasValidWindow: Boolean get() = sanitized.let { it.startHour < it.endHour }
}

/** Minimal view of a pending lecture for reminder purposes. */
data class PendingLecture(
    val date: LocalDate,
    val order: Int,
    val subject: String,
    val title: String,
)

sealed interface ReminderDecision {
    data class Notify(val count: Int, val message: String, val slot: LocalDateTime) : ReminderDecision
    data class Skip(val reason: SkipReason) : ReminderDecision
}

enum class SkipReason { DISABLED, INVALID_WINDOW, OUTSIDE_HOURS, NOTHING_PENDING, ALREADY_REMINDED_THIS_SLOT }

/**
 * Pure reminder logic shared by [com.deeproot.app.reminders.ReminderWorker] and the FCM fallback handler
 * (the same rules are ported to TypeScript in `functions/src/reminderRules.ts`).
 *
 * The day is split into *slots*: startHour, startHour + interval, … (strictly before endHour).
 * With the defaults (8–22, every 2 h) the slots are 08:00, 10:00, …, 20:00. At most one reminder is sent per
 * slot, no matter how often or how irregularly the background job runs. This makes reminders robust to
 * WorkManager drift and Doze batching: a late run still produces exactly one reminder for its slot, and an
 * early run (within [TOLERANCE]) counts towards the upcoming slot.
 */
object ReminderRules {

    /** Background work may run a little early; a run this close to the next slot counts for that slot. */
    val TOLERANCE: Duration = Duration.ofMinutes(15)

    /** Today's lectures first (by order), then the backlog (oldest first). Future lectures are dropped. */
    fun orderForReminder(pending: List<PendingLecture>, today: LocalDate): List<PendingLecture> {
        val todays = pending.filter { it.date == today }.sortedBy { it.order }
        val backlog = pending.filter { it.date.isBefore(today) }
            .sortedWith(compareBy<PendingLecture>({ it.date }, { it.order }))
        return todays + backlog
    }

    /** "3 lectures pending. Next: Physics – Rotation 2" — `null` when nothing is pending. */
    fun buildMessage(ordered: List<PendingLecture>): String? {
        val next = ordered.firstOrNull() ?: return null
        val count = ordered.size
        val noun = if (count == 1) "lecture" else "lectures"
        return "$count $noun pending. Next: ${next.subject} – ${next.title}"
    }

    /** All reminder slots on [date]. */
    fun slotsOn(date: LocalDate, settings: ReminderSettings): List<LocalDateTime> {
        val s = settings.sanitized
        if (s.startHour >= s.endHour) return emptyList()
        return (s.startHour until s.endHour step s.intervalHours).map { hour -> date.atTime(hour, 0) }
    }

    /** The latest slot at or before [time] on the same day, or `null` before the first slot. */
    fun currentSlot(time: LocalDateTime, settings: ReminderSettings): LocalDateTime? =
        slotsOn(time.toLocalDate(), settings).lastOrNull { !it.isAfter(time) }

    /** True when [time] is inside the reminder window [startHour, endHour). */
    fun isWithinWindow(time: LocalDateTime, settings: ReminderSettings): Boolean {
        val s = settings.sanitized
        if (s.startHour >= s.endHour) return false
        val date = time.toLocalDate()
        val start = date.atTime(s.startHour, 0)
        val end = if (s.endHour >= 24) date.plusDays(1).atStartOfDay() else date.atTime(s.endHour, 0)
        return !time.isBefore(start) && time.isBefore(end)
    }

    /** The slot a run at [now] counts towards (looking ahead by [TOLERANCE] within the same day). */
    fun effectiveSlot(now: LocalDateTime, settings: ReminderSettings): LocalDateTime? {
        val probe = now.plus(TOLERANCE)
        return if (probe.toLocalDate() == now.toLocalDate()) currentSlot(probe, settings) else currentSlot(now, settings)
    }

    /** True if a reminder was already shown for the slot that [now] belongs to. */
    fun wasRemindedThisSlot(now: LocalDateTime, lastNotifiedAt: LocalDateTime?, settings: ReminderSettings): Boolean {
        if (lastNotifiedAt == null) return false
        val slot = effectiveSlot(now, settings) ?: return false
        return !lastNotifiedAt.isBefore(slot.minus(TOLERANCE))
    }

    /**
     * Decides whether to show a reminder now.
     * @param pending lectures that are not completed and dated today or earlier (future ones are ignored).
     * @param lastNotifiedAt when the last reminder (local or push) was shown, in local time.
     */
    fun decide(
        now: LocalDateTime,
        settings: ReminderSettings,
        pending: List<PendingLecture>,
        lastNotifiedAt: LocalDateTime?,
    ): ReminderDecision {
        if (!settings.enabled) return ReminderDecision.Skip(SkipReason.DISABLED)
        val s = settings.sanitized
        if (s.startHour >= s.endHour) return ReminderDecision.Skip(SkipReason.INVALID_WINDOW)
        // Never remind outside the user's hours (strict at the start of the window: no 7:50 reminders for an 8:00 start).
        if (!isWithinWindow(now, s)) return ReminderDecision.Skip(SkipReason.OUTSIDE_HOURS)
        val slot = effectiveSlot(now, s) ?: return ReminderDecision.Skip(SkipReason.OUTSIDE_HOURS)
        if (lastNotifiedAt != null && !lastNotifiedAt.isBefore(slot.minus(TOLERANCE))) {
            return ReminderDecision.Skip(SkipReason.ALREADY_REMINDED_THIS_SLOT)
        }
        val ordered = orderForReminder(pending, now.toLocalDate())
        val message = buildMessage(ordered) ?: return ReminderDecision.Skip(SkipReason.NOTHING_PENDING)
        return ReminderDecision.Notify(count = ordered.size, message = message, slot = slot)
    }

    /** The first slot strictly after [now] (today or a later day). Used to align the periodic job. */
    fun nextSlotAfter(now: LocalDateTime, settings: ReminderSettings): LocalDateTime? {
        if (!settings.sanitized.hasValidWindow) return null
        var date = now.toLocalDate()
        repeat(3) {
            slotsOn(date, settings).firstOrNull { it.isAfter(now) }?.let { return it }
            date = date.plusDays(1)
        }
        return null
    }
}
