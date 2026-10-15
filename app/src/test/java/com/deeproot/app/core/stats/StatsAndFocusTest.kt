package com.deeproot.app.core.stats

import com.deeproot.app.core.focus.FocusPolicy
import com.deeproot.app.core.model.Lecture
import com.deeproot.app.core.model.LectureSource
import com.deeproot.app.core.model.StudySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatsAndFocusTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 10, 20)
    private fun millisAt(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun lecture(id: String, date: LocalDate, subject: String, completedOn: LocalDate? = null, studied: Long = 0) = Lecture(
        id = id,
        date = date,
        order = 1,
        subject = subject,
        title = id,
        completed = completedOn != null,
        completedAt = completedOn?.let { millisAt(it, 18) },
        plannedMinutes = 120,
        studiedSeconds = studied,
        source = LectureSource.PLAN,
        calendarEventId = null,
        updatedAt = 1,
    )

    @Test
    fun `streak counts consecutive days and tolerates today not done yet`() {
        val active = setOf(today.minusDays(1), today.minusDays(2), today.minusDays(3))
        assertEquals(3, StatsCalculator.streak(active, emptySet(), today))
        assertEquals(4, StatsCalculator.streak(active + today, emptySet(), today))
    }

    @Test
    fun `streak breaks on a missed day but not on a rest day`() {
        val active = setOf(today, today.minusDays(1), today.minusDays(3))
        assertEquals(2, StatsCalculator.streak(active, emptySet(), today))
        assertEquals(3, StatsCalculator.streak(active, setOf(today.minusDays(2)), today))
    }

    @Test
    fun `no activity means zero streak`() {
        assertEquals(0, StatsCalculator.streak(emptySet(), emptySet(), today))
    }

    @Test
    fun `weekly totals and today`() {
        val sessions = listOf(
            StudySession("s1", "a", millisAt(today, 9), millisAt(today, 11), 120, 7200),
            StudySession("s2", "b", millisAt(today.minusDays(1), 9), millisAt(today.minusDays(1), 10), 60, 3600),
            StudySession("s3", "c", millisAt(today.minusDays(10), 9), millisAt(today.minusDays(10), 10), 60, 3600),
        )
        val lectures = listOf(
            lecture("a", today, "Physics", completedOn = today, studied = 7200),
            lecture("b", today.minusDays(1), "Maths", completedOn = today.minusDays(1), studied = 3600),
            lecture("c", today, "Physics"),
        )
        val stats = StatsCalculator.compute(lectures, sessions, today, zone)
        assertEquals(7200, stats.todayStudiedSeconds)
        assertEquals(1, stats.todayCompleted)
        assertEquals(7, stats.week.size)
        assertEquals(today, stats.week.last().date)
        assertEquals(3600, stats.week[5].studiedSeconds)
        assertEquals(2, stats.streakDays)
        assertEquals(3, stats.totalLectures)
        assertEquals(2, stats.totalCompleted)
        assertEquals(14_400, stats.totalStudiedSeconds)

        val physics = stats.subjects.first { it.subject == "Physics" }
        assertEquals(2, physics.total)
        assertEquals(1, physics.completed)
        assertEquals(0.5f, physics.fraction, 0.001f)
        assertEquals(listOf("Physics", "Maths"), stats.subjects.map { it.subject })
    }

    @Test
    fun `rest days are plan gaps`() {
        val lectures = listOf(
            lecture("a", today.minusDays(3), "Physics"),
            lecture("b", today, "Physics"),
        )
        assertEquals(setOf(today.minusDays(2), today.minusDays(1)), StatsCalculator.restDays(lectures))
    }

    @Test
    fun `focus policy never blocks own app, allowed apps, dialer, emergency or settings`() {
        val own = "com.deeproot.app"
        val allowed = setOf("xyz.penpencil.physicswala")
        val device = setOf("com.sec.android.app.launcher")
        fun blocked(pkg: String?) = FocusPolicy.shouldBlock(pkg, own, allowed, device)

        assertFalse(blocked(own))
        assertFalse(blocked("xyz.penpencil.physicswala"))
        assertFalse(blocked("com.sec.android.app.launcher"))
        assertFalse(blocked("com.google.android.dialer"))
        assertFalse(blocked("com.android.phone"))
        assertFalse(blocked("com.android.emergency"))
        assertFalse(blocked("com.android.settings"))
        assertFalse(blocked("com.android.settings.intelligence"))
        assertFalse(blocked("com.android.systemui"))
        assertFalse(blocked("com.google.android.permissioncontroller"))
        assertFalse(blocked(null))
        assertFalse(blocked(""))

        assertTrue(blocked("com.instagram.android"))
        assertTrue(blocked("com.google.android.youtube"))
        // Prefix matching is per package segment: "android" must not allow "androidx.fake".
        assertTrue(blocked("androidx.fake"))
        assertTrue(blocked("com.android.settingsfake"))
    }
}
