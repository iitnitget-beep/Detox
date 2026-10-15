package com.deeproot.app.core.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CalendarEventMapperTest {

    private val ist = ZoneId.of("Asia/Kolkata")

    @Test
    fun `timed event is converted to the device time zone`() {
        val input = CalendarEventMapper.toInput(
            eventId = "e1",
            status = "confirmed",
            summary = "Physics – Rotation 3",
            startDate = null,
            startDateTime = "2026-10-20T04:30:00Z",
            endDateTime = "2026-10-20T06:00:00Z",
            zone = ist,
        )!!
        assertEquals(LocalDate.of(2026, 10, 20), input.date)
        assertEquals(10 * 60, input.startMinuteOfDay)
        assertEquals(90, input.durationMinutes)
    }

    @Test
    fun `late evening UTC event lands on the next local day`() {
        val input = CalendarEventMapper.toInput("e2", null, "Maths", null, "2026-10-20T20:00:00+00:00", null, ist)!!
        assertEquals(LocalDate.of(2026, 10, 21), input.date)
        assertEquals(90, input.startMinuteOfDay)
        assertNull(input.durationMinutes)
    }

    @Test
    fun `all day event keeps its date and has no time`() {
        val input = CalendarEventMapper.toInput("e3", null, "Mock test", "2026-10-22", null, null, ist)!!
        assertEquals(LocalDate.of(2026, 10, 22), input.date)
        assertNull(input.startMinuteOfDay)
        assertNull(input.durationMinutes)
    }

    @Test
    fun `cancelled flag, missing id and bad dates`() {
        assertTrue(CalendarEventMapper.toInput("e4", "cancelled", "x", "2026-10-22", null, null, ist)!!.cancelled)
        assertNull(CalendarEventMapper.toInput(null, null, "x", "2026-10-22", null, null, ist))
        assertNull(CalendarEventMapper.toInput("e5", null, "x", null, "not a date", null, ist))
        assertNull(CalendarEventMapper.toInput("e6", null, "x", null, null, null, ist))
    }

    @Test
    fun `mapped events feed the diff`() {
        val event = CalendarEventMapper.toInput("abc", null, "GOC lecture", null, "2026-10-20T10:00:00+05:30", "2026-10-20T11:30:00+05:30", ist)!!
        val fields = CalendarDiff.toFields(event)
        assertEquals("cal_abc", fields.id)
        assertEquals("Organic", fields.subject)
        assertEquals(1000 + 600, fields.order)
        assertEquals(90, fields.plannedMinutes)
    }
}
