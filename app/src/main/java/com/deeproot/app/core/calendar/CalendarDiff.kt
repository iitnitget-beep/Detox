package com.deeproot.app.core.calendar

import com.deeproot.app.core.model.Lecture
import java.time.LocalDate

/** A Google Calendar event, already converted to the device time zone. */
data class CalendarEventInput(
    val eventId: String,
    val title: String,
    val date: LocalDate,
    /** Minutes after local midnight; `null` for all-day events. */
    val startMinuteOfDay: Int?,
    /** Event length in minutes; `null` for all-day events. */
    val durationMinutes: Int?,
    val cancelled: Boolean = false,
)

/** The lecture fields derived from one calendar event. */
data class CalendarLectureFields(
    val id: String,
    val calendarEventId: String,
    val date: LocalDate,
    val order: Int,
    val subject: String,
    val title: String,
    val plannedMinutes: Int,
)

/** A calendar-sourced lecture that already exists locally/remotely. */
data class ExistingCalendarLecture(
    val id: String,
    val calendarEventId: String,
    val date: LocalDate,
    val order: Int,
    val subject: String,
    val title: String,
    val plannedMinutes: Int,
    val completed: Boolean,
)

data class CalendarDiffResult(
    val inserts: List<CalendarLectureFields>,
    val updates: List<CalendarLectureFields>,
    val deletes: List<String>,
    /** Lectures whose event disappeared but which were kept because they are already completed. */
    val keptCompleted: Int,
) {
    val isEmpty: Boolean get() = inserts.isEmpty() && updates.isEmpty() && deletes.isEmpty()
}

/**
 * Pure diff between the events currently in the selected calendar and the calendar lectures we already have.
 *
 * - New event → insert (deduplicated by `calendarEventId`; document id = `cal_<eventId>`).
 * - Changed title/date/time/duration → update (completion state and studied time are preserved).
 * - Event deleted or moved out of the sync window → delete, **unless the lecture is already completed**.
 * - Lectures outside the sync window (e.g. past ones) are never touched.
 */
object CalendarDiff {
    /** Calendar lectures sort after plan lectures on the same day, in start-time order. */
    const val ORDER_BASE: Int = 1000
    const val MAX_TITLE_LENGTH: Int = 200
    const val MIN_PLANNED_MINUTES: Int = 15
    const val MAX_PLANNED_MINUTES: Int = 720

    private val UNSAFE_ID_CHARS = Regex("[^A-Za-z0-9_-]")

    fun lectureIdFor(eventId: String): String = "cal_" + eventId.replace(UNSAFE_ID_CHARS, "_")

    fun toFields(event: CalendarEventInput, defaultPlannedMinutes: Int = Lecture.DEFAULT_PLANNED_MINUTES): CalendarLectureFields {
        val title = event.title.trim().ifEmpty { "(Untitled event)" }.take(MAX_TITLE_LENGTH)
        val planned = event.durationMinutes
            ?.takeIf { it > 0 }
            ?.coerceIn(MIN_PLANNED_MINUTES, MAX_PLANNED_MINUTES)
            ?: defaultPlannedMinutes
        return CalendarLectureFields(
            id = lectureIdFor(event.eventId),
            calendarEventId = event.eventId,
            date = event.date,
            order = ORDER_BASE + (event.startMinuteOfDay ?: 0).coerceIn(0, 24 * 60 - 1),
            subject = SubjectGuesser.guess(title).label,
            title = title,
            plannedMinutes = planned,
        )
    }

    /**
     * @param windowStart first day of the sync window (inclusive), normally today.
     * @param windowEndExclusive day after the last synced day (today + 60 days).
     */
    fun compute(
        events: List<CalendarEventInput>,
        existing: List<ExistingCalendarLecture>,
        windowStart: LocalDate,
        windowEndExclusive: LocalDate,
        defaultPlannedMinutes: Int = Lecture.DEFAULT_PLANNED_MINUTES,
    ): CalendarDiffResult {
        fun inWindow(date: LocalDate) = !date.isBefore(windowStart) && date.isBefore(windowEndExclusive)

        val desired: Map<String, CalendarLectureFields> = events
            .filter { !it.cancelled && inWindow(it.date) }
            .associateBy({ it.eventId }, { toFields(it, defaultPlannedMinutes) })

        val existingByEvent = existing.associateBy { it.calendarEventId }

        val inserts = desired.values.filter { it.calendarEventId !in existingByEvent }

        val updates = desired.values.mapNotNull { wanted ->
            val current = existingByEvent[wanted.calendarEventId] ?: return@mapNotNull null
            val changed = current.date != wanted.date ||
                current.order != wanted.order ||
                current.subject != wanted.subject ||
                current.title != wanted.title ||
                current.plannedMinutes != wanted.plannedMinutes
            if (changed) wanted.copy(id = current.id) else null
        }

        val vanished = existing.filter { it.calendarEventId !in desired && inWindow(it.date) }
        return CalendarDiffResult(
            inserts = inserts.sortedWith(compareBy({ it.date }, { it.order })),
            updates = updates.sortedWith(compareBy({ it.date }, { it.order })),
            deletes = vanished.filter { !it.completed }.map { it.id },
            keptCompleted = vanished.count { it.completed },
        )
    }
}
