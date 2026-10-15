package com.deeproot.app.core.calendar

import com.deeproot.app.core.model.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalendarSyncLogicTest {

    private val today = LocalDate.of(2026, 10, 20)
    private val windowEnd = today.plusDays(60)

    private fun event(id: String, title: String, date: LocalDate = today, start: Int? = 600, duration: Int? = 90) =
        CalendarEventInput(eventId = id, title = title, date = date, startMinuteOfDay = start, durationMinutes = duration)

    private fun existing(event: CalendarEventInput, completed: Boolean = false): ExistingCalendarLecture {
        val f = CalendarDiff.toFields(event)
        return ExistingCalendarLecture(f.id, f.calendarEventId, f.date, f.order, f.subject, f.title, f.plannedMinutes, completed)
    }

    @Test
    fun `subject guessing from keywords`() {
        assertEquals(Subject.PHYSICS, SubjectGuesser.guess("Physics live class"))
        assertEquals(Subject.PHYSICS, SubjectGuesser.guess("Rotation 3"))
        assertEquals(Subject.MATHS, SubjectGuesser.guess("Maths: Definite Integration"))
        assertEquals(Subject.MATHS, SubjectGuesser.guess("Matrix doubt session"))
        assertEquals(Subject.INORGANIC, SubjectGuesser.guess("Inorganic chem revision"))
        assertEquals(Subject.ORGANIC, SubjectGuesser.guess("Organic - GOC 4"))
        assertEquals(Subject.ORGANIC, SubjectGuesser.guess("Aldehyde Ketone practice"))
        assertEquals(Subject.PHYSICAL_CHEM, SubjectGuesser.guess("Physical Chemistry: Ionic Equilibrium"))
        assertEquals(Subject.PHYSICAL_CHEM, SubjectGuesser.guess("Thermodynamics 2"))
        assertEquals(Subject.PHYSICAL_CHEM, SubjectGuesser.guess("Chem class"))
        assertEquals(Subject.OTHER, SubjectGuesser.guess("Dentist appointment"))
        assertEquals(Subject.OTHER, SubjectGuesser.guess(""))
        assertEquals(Subject.OTHER, SubjectGuesser.guess(null))
    }

    @Test
    fun `inorganic is not mistaken for organic`() {
        assertEquals(Subject.INORGANIC, SubjectGuesser.guess("INORGANIC"))
        assertEquals(Subject.ORGANIC, SubjectGuesser.guess("organic"))
    }

    @Test
    fun `whole word matching avoids false positives`() {
        // "practice" contains "ac", "graphy" contains "phy" — neither should match.
        assertEquals(Subject.OTHER, SubjectGuesser.guess("Piano practice"))
        assertEquals(Subject.OTHER, SubjectGuesser.guess("Photography club"))
    }

    @Test
    fun `fields derived from an event`() {
        val f = CalendarDiff.toFields(event("abc123", "  Physics: Optics 2 ", start = 9 * 60 + 30, duration = 150))
        assertEquals("cal_abc123", f.id)
        assertEquals("Physics: Optics 2", f.title)
        assertEquals("Physics", f.subject)
        assertEquals(CalendarDiff.ORDER_BASE + 570, f.order)
        assertEquals(150, f.plannedMinutes)
    }

    @Test
    fun `all day events use the default duration and order base`() {
        val f = CalendarDiff.toFields(event("x", "Maths test", start = null, duration = null))
        assertEquals(120, f.plannedMinutes)
        assertEquals(CalendarDiff.ORDER_BASE, f.order)
    }

    @Test
    fun `durations are clamped and titles truncated`() {
        assertEquals(15, CalendarDiff.toFields(event("a", "Short", duration = 5)).plannedMinutes)
        assertEquals(720, CalendarDiff.toFields(event("b", "Long", duration = 2000)).plannedMinutes)
        assertEquals(200, CalendarDiff.toFields(event("c", "x".repeat(500))).title.length)
        assertEquals("(Untitled event)", CalendarDiff.toFields(event("d", "   ")).title)
    }

    @Test
    fun `unsafe characters are removed from document ids`() {
        assertEquals("cal_a_b_c", CalendarDiff.lectureIdFor("a/b.c"))
        assertEquals("cal_evt_20261020T100000Z", CalendarDiff.lectureIdFor("evt_20261020T100000Z"))
    }

    @Test
    fun `new events are inserted once`() {
        val events = listOf(event("e1", "Physics 1"), event("e2", "Maths 1", today.plusDays(1)))
        val diff = CalendarDiff.compute(events, emptyList(), today, windowEnd)
        assertEquals(listOf("cal_e1", "cal_e2"), diff.inserts.map { it.id })
        assertTrue(diff.updates.isEmpty() && diff.deletes.isEmpty())

        // Running the sync again with the same events and the inserted lectures → nothing to do (no duplicates).
        val again = CalendarDiff.compute(events, events.map { existing(it) }, today, windowEnd)
        assertTrue(again.isEmpty)
    }

    @Test
    fun `changed events are updated and keep their id`() {
        val original = event("e1", "Physics 1")
        val moved = original.copy(title = "Physics 1 (rescheduled)", date = today.plusDays(2), startMinuteOfDay = 900)
        val diff = CalendarDiff.compute(listOf(moved), listOf(existing(original)), today, windowEnd)
        assertEquals(1, diff.updates.size)
        val update = diff.updates.single()
        assertEquals("cal_e1", update.id)
        assertEquals(today.plusDays(2), update.date)
        assertEquals("Physics 1 (rescheduled)", update.title)
        assertTrue(diff.inserts.isEmpty() && diff.deletes.isEmpty())
    }

    @Test
    fun `deleted events are removed only when not completed`() {
        val pendingOne = existing(event("gone1", "Maths 3"))
        val doneOne = existing(event("gone2", "Physics 9"), completed = true)
        val diff = CalendarDiff.compute(emptyList(), listOf(pendingOne, doneOne), today, windowEnd)
        assertEquals(listOf("cal_gone1"), diff.deletes)
        assertEquals(1, diff.keptCompleted)
    }

    @Test
    fun `cancelled events count as deleted`() {
        val e = event("e1", "Physics 1")
        val diff = CalendarDiff.compute(listOf(e.copy(cancelled = true)), listOf(existing(e)), today, windowEnd)
        assertEquals(listOf("cal_e1"), diff.deletes)
    }

    @Test
    fun `lectures outside the window are never touched`() {
        val past = existing(event("old", "Physics old", date = today.minusDays(3)))
        val diff = CalendarDiff.compute(emptyList(), listOf(past), today, windowEnd)
        assertTrue(diff.isEmpty)
    }

    @Test
    fun `events outside the window are ignored`() {
        val diff = CalendarDiff.compute(listOf(event("far", "Maths", date = windowEnd)), emptyList(), today, windowEnd)
        assertTrue(diff.isEmpty)
    }
}
