package com.deeproot.app.core.calendar

import com.deeproot.app.core.time.Dates
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Converts a Google Calendar event (raw API fields) to a [CalendarEventInput] in the device time zone. */
object CalendarEventMapper {

    /**
     * @param startDate all-day events: `yyyy-MM-dd`
     * @param startDateTime / [endDateTime] timed events: RFC 3339 with offset
     * @return null when the event has no id or no usable start
     */
    fun toInput(
        eventId: String?,
        status: String?,
        summary: String?,
        startDate: String?,
        startDateTime: String?,
        endDateTime: String?,
        zone: ZoneId,
    ): CalendarEventInput? {
        val id = eventId?.takeIf { it.isNotBlank() } ?: return null
        val cancelled = status == "cancelled"
        val title = summary.orEmpty()
        if (startDate != null) {
            val date = Dates.parseIsoOrNull(startDate) ?: return null
            return CalendarEventInput(id, title, date, startMinuteOfDay = null, durationMinutes = null, cancelled = cancelled)
        }
        val start = parse(startDateTime) ?: return null
        val end = parse(endDateTime)
        val local = start.atZoneSameInstant(zone)
        return CalendarEventInput(
            eventId = id,
            title = title,
            date = local.toLocalDate(),
            startMinuteOfDay = local.hour * 60 + local.minute,
            durationMinutes = end?.let { Duration.between(start, it).toMinutes().toInt() },
            cancelled = cancelled,
        )
    }

    private fun parse(text: String?): OffsetDateTime? = try {
        if (text.isNullOrBlank()) null else OffsetDateTime.parse(text)
    } catch (e: DateTimeParseException) {
        null
    }
}
