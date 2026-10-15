package com.deeproot.app.core.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReminderRulesTest {

    private val today = LocalDate.of(2026, 10, 20)
    private val defaults = ReminderSettings() // every 2h, 08:00–22:00
    private fun at(h: Int, m: Int = 0, date: LocalDate = today) = date.atTime(h, m)

    private val pending = listOf(
        PendingLecture(today.minusDays(2), 3, "Organic", "IUPAC 4"),
        PendingLecture(today, 2, "Physics", "Rotation 2"),
        PendingLecture(today, 1, "Physics", "Rotation 1"),
        PendingLecture(today.minusDays(1), 1, "Maths", "Determinant 4"),
    )

    @Test
    fun `message matches the required format`() {
        val ordered = ReminderRules.orderForReminder(
            listOf(
                PendingLecture(today, 2, "Physics", "Rotation 3"),
                PendingLecture(today, 1, "Physics", "Rotation 2"),
                PendingLecture(today.minusDays(1), 1, "Maths", "Matrix 1"),
            ),
            today,
        )
        assertEquals("3 lectures pending. Next: Physics – Rotation 2", ReminderRules.buildMessage(ordered))
    }

    @Test
    fun `singular message`() {
        val ordered = listOf(PendingLecture(today, 1, "Maths", "Matrix 1"))
        assertEquals("1 lecture pending. Next: Maths – Matrix 1", ReminderRules.buildMessage(ordered))
    }

    @Test
    fun `no message when nothing is pending`() {
        assertNull(ReminderRules.buildMessage(emptyList()))
    }

    @Test
    fun `today comes first, then backlog oldest first, future dropped`() {
        val withFuture = pending + PendingLecture(today.plusDays(1), 1, "Physics", "Future 1")
        val ordered = ReminderRules.orderForReminder(withFuture, today)
        assertEquals(listOf("Rotation 1", "Rotation 2", "IUPAC 4", "Determinant 4"), ordered.map { it.title })
    }

    @Test
    fun `backlog only uses the oldest lecture as next`() {
        val backlogOnly = listOf(
            PendingLecture(today.minusDays(1), 1, "Maths", "Matrix 1"),
            PendingLecture(today.minusDays(3), 2, "Organic", "GOC 2"),
        )
        val decision = ReminderRules.decide(at(10), defaults, backlogOnly, null)
        assertEquals("2 lectures pending. Next: Organic – GOC 2", (decision as ReminderDecision.Notify).message)
    }

    @Test
    fun `default slots are every two hours from 8 to 20`() {
        val slots = ReminderRules.slotsOn(today, defaults).map { it.hour }
        assertEquals(listOf(8, 10, 12, 14, 16, 18, 20), slots)
    }

    @Test
    fun `notifies inside the window when lectures are pending`() {
        val decision = ReminderRules.decide(at(10, 5), defaults, pending, lastNotifiedAt = null)
        assertTrue(decision is ReminderDecision.Notify)
        decision as ReminderDecision.Notify
        assertEquals(4, decision.count)
        assertEquals(at(10), decision.slot)
        assertEquals("4 lectures pending. Next: Physics – Rotation 1", decision.message)
    }

    @Test
    fun `skips before the window start and at or after the end`() {
        assertEquals(ReminderDecision.Skip(SkipReason.OUTSIDE_HOURS), ReminderRules.decide(at(7, 50), defaults, pending, null))
        assertEquals(ReminderDecision.Skip(SkipReason.OUTSIDE_HOURS), ReminderRules.decide(at(22, 0), defaults, pending, null))
        assertEquals(ReminderDecision.Skip(SkipReason.OUTSIDE_HOURS), ReminderRules.decide(at(23, 30), defaults, pending, null))
        assertTrue(ReminderRules.decide(at(8, 0), defaults, pending, null) is ReminderDecision.Notify)
        assertTrue(ReminderRules.decide(at(21, 59), defaults, pending, null) is ReminderDecision.Notify)
    }

    @Test
    fun `skips when nothing is pending`() {
        assertEquals(ReminderDecision.Skip(SkipReason.NOTHING_PENDING), ReminderRules.decide(at(12), defaults, emptyList(), null))
    }

    @Test
    fun `skips when disabled`() {
        val off = defaults.copy(enabled = false)
        assertEquals(ReminderDecision.Skip(SkipReason.DISABLED), ReminderRules.decide(at(12), off, pending, null))
    }

    @Test
    fun `invalid window never notifies`() {
        val bad = defaults.copy(startHour = 22, endHour = 8)
        assertEquals(ReminderDecision.Skip(SkipReason.INVALID_WINDOW), ReminderRules.decide(at(12), bad, pending, null))
        assertFalse(bad.hasValidWindow)
    }

    @Test
    fun `at most one reminder per slot`() {
        val first = ReminderRules.decide(at(10, 2), defaults, pending, null)
        assertTrue(first is ReminderDecision.Notify)
        // Worker runs again 40 minutes later: same slot → skip.
        assertEquals(
            ReminderDecision.Skip(SkipReason.ALREADY_REMINDED_THIS_SLOT),
            ReminderRules.decide(at(10, 42), defaults, pending, lastNotifiedAt = at(10, 2)),
        )
        // Next slot at 12:00 → notify again.
        assertTrue(ReminderRules.decide(at(12, 1), defaults, pending, lastNotifiedAt = at(10, 2)) is ReminderDecision.Notify)
    }

    @Test
    fun `slightly early run counts for the upcoming slot without double notifying`() {
        // Run at 11:50 (10 min before the 12:00 slot), last reminder was at 10:00.
        val early = ReminderRules.decide(at(11, 50), defaults, pending, lastNotifiedAt = at(10, 0))
        assertTrue(early is ReminderDecision.Notify)
        assertEquals(at(12), (early as ReminderDecision.Notify).slot)
        // The regular 12:05 run must not notify again.
        assertEquals(
            ReminderDecision.Skip(SkipReason.ALREADY_REMINDED_THIS_SLOT),
            ReminderRules.decide(at(12, 5), defaults, pending, lastNotifiedAt = at(11, 50)),
        )
    }

    @Test
    fun `late run still reminds once for its slot`() {
        // Doze delayed the 10:00 run to 11:40; last reminder was the 08:00 slot.
        val late = ReminderRules.decide(at(11, 40), defaults, pending, lastNotifiedAt = at(8, 3))
        assertTrue(late is ReminderDecision.Notify)
        assertEquals(at(10), (late as ReminderDecision.Notify).slot)
    }

    @Test
    fun `yesterday's reminder does not block today`() {
        val decision = ReminderRules.decide(at(8, 1), defaults, pending, lastNotifiedAt = at(20, 0, today.minusDays(1)))
        assertTrue(decision is ReminderDecision.Notify)
    }

    @Test
    fun `custom interval and window`() {
        val custom = ReminderSettings(enabled = true, intervalHours = 3, startHour = 9, endHour = 21)
        assertEquals(listOf(9, 12, 15, 18), ReminderRules.slotsOn(today, custom).map { it.hour })
        assertEquals(at(12), ReminderRules.currentSlot(at(14, 59), custom))
        assertNull(ReminderRules.currentSlot(at(8, 59), custom))
    }

    @Test
    fun `window ending at midnight`() {
        val late = ReminderSettings(enabled = true, intervalHours = 4, startHour = 12, endHour = 24)
        assertTrue(ReminderRules.isWithinWindow(at(23, 59), late))
        assertFalse(ReminderRules.isWithinWindow(at(0, 30), late))
        assertEquals(listOf(12, 16, 20), ReminderRules.slotsOn(today, late).map { it.hour })
    }

    @Test
    fun `values are clamped to allowed ranges`() {
        val wild = ReminderSettings(enabled = true, intervalHours = 0, startHour = -3, endHour = 99).sanitized
        assertEquals(1, wild.intervalHours)
        assertEquals(0, wild.startHour)
        assertEquals(24, wild.endHour)
    }

    @Test
    fun `next slot after now`() {
        assertEquals(at(10), ReminderRules.nextSlotAfter(at(9, 30), defaults))
        assertEquals(at(10), ReminderRules.nextSlotAfter(at(8, 0), defaults))
        assertEquals(at(8, 0, today.plusDays(1)), ReminderRules.nextSlotAfter(at(20, 0), defaults))
        assertEquals(at(8, 0, today.plusDays(1)), ReminderRules.nextSlotAfter(at(23, 0), defaults))
        assertNull(ReminderRules.nextSlotAfter(at(9), defaults.copy(startHour = 10, endHour = 10)))
    }

    @Test
    fun `push fallback is suppressed when the device already reminded this slot`() {
        assertTrue(ReminderRules.wasRemindedThisSlot(at(10, 40), at(10, 1), defaults))
        assertFalse(ReminderRules.wasRemindedThisSlot(at(12, 30), at(10, 1), defaults))
        assertFalse(ReminderRules.wasRemindedThisSlot(at(12, 30), null, defaults))
    }

    @Test
    fun `slot computation near midnight stays on the same day`() {
        val allDay = ReminderSettings(enabled = true, intervalHours = 1, startHour = 0, endHour = 24)
        val slot = ReminderRules.effectiveSlot(LocalDateTime.of(2026, 10, 20, 23, 50), allDay)
        assertEquals(at(23), slot)
    }
}
