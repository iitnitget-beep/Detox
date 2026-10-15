package com.deeproot.app.core.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerStateTest {

    private val t0 = 1_760_000_000_000L // arbitrary wall-clock origin
    private fun min(m: Long) = m * 60_000L
    private fun sec(s: Long) = s * 1_000L

    @Test
    fun `start begins running with nothing studied`() {
        val timer = TimerState.start(t0, plannedMinutes = 120)
        assertTrue(timer.isRunning)
        assertEquals(0L, timer.studiedMillis(t0))
        assertEquals(min(120), timer.remainingMillis(t0))
        assertEquals(0f, timer.progress(t0), 0.0001f)
    }

    @Test
    fun `studied time is derived from timestamps`() {
        val timer = TimerState.start(t0, 120)
        assertEquals(min(10), timer.studiedMillis(t0 + min(10)))
        assertEquals(min(110), timer.remainingMillis(t0 + min(10)))
        assertEquals(600L, timer.studiedSeconds(t0 + min(10)))
    }

    @Test
    fun `pause banks time and stops the clock`() {
        val paused = TimerState.start(t0, 60).pause(t0 + min(5))
        assertFalse(paused.isRunning)
        assertEquals(min(5), paused.studiedMillis(t0 + min(5)))
        // An hour later the studied time is unchanged while paused.
        assertEquals(min(5), paused.studiedMillis(t0 + min(65)))
    }

    @Test
    fun `resume excludes the paused interval`() {
        val timer = TimerState.start(t0, 60)
            .pause(t0 + min(10))
            .resume(t0 + min(30))
        // 10 min before pause + 15 min after resume
        assertEquals(min(25), timer.studiedMillis(t0 + min(45)))
    }

    @Test
    fun `multiple pause resume cycles accumulate correctly`() {
        var timer = TimerState.start(t0, 120)
        timer = timer.pause(t0 + min(20))   // 20
        timer = timer.resume(t0 + min(25))
        timer = timer.pause(t0 + min(40))   // +15 = 35
        timer = timer.resume(t0 + min(60))
        timer = timer.pause(t0 + min(61))   // +1 = 36
        assertEquals(min(36), timer.studiedMillis(t0 + min(500)))
    }

    @Test
    fun `pause and resume are idempotent`() {
        val running = TimerState.start(t0, 30)
        assertEquals(running, running.resume(t0 + min(1)))
        val paused = running.pause(t0 + min(2))
        assertEquals(paused, paused.pause(t0 + min(3)))
    }

    @Test
    fun `state survives process death because only timestamps are stored`() {
        val beforeKill = TimerState.start(t0, 90).pause(t0 + min(30)).resume(t0 + min(40))
        // Simulate persisting the three fields and reading them back after the app was killed.
        val restored = TimerState(
            plannedMillis = beforeKill.plannedMillis,
            accumulatedMillis = beforeKill.accumulatedMillis,
            runningSinceMillis = beforeKill.runningSinceMillis,
        )
        val now = t0 + min(100) // app reopened much later
        assertEquals(beforeKill.studiedMillis(now), restored.studiedMillis(now))
        assertEquals(min(90), restored.studiedMillis(now)) // 30 + 60
    }

    @Test
    fun `remaining becomes negative in overtime`() {
        val timer = TimerState.start(t0, 30)
        val now = t0 + min(35)
        assertTrue(timer.isPlannedTimeReached(now))
        assertEquals(-min(5), timer.remainingMillis(now))
        assertEquals(min(5), timer.overtimeMillis(now))
        assertEquals(1f, timer.progress(now), 0.0001f)
    }

    @Test
    fun `planned time reached exactly at the boundary`() {
        val timer = TimerState.start(t0, 30)
        assertFalse(timer.isPlannedTimeReached(t0 + min(30) - 1))
        assertTrue(timer.isPlannedTimeReached(t0 + min(30)))
    }

    @Test
    fun `clock going backwards never produces negative time`() {
        val timer = TimerState.start(t0, 30)
        assertEquals(0L, timer.studiedMillis(t0 - min(10)))
    }

    @Test
    fun `planned end is projected only while running`() {
        val timer = TimerState.start(t0, 60)
        assertEquals(t0 + min(60), timer.plannedEndAtMillis(t0 + min(10)))
        assertNull(timer.pause(t0 + min(10)).plannedEndAtMillis(t0 + min(20)))
        assertNull(timer.plannedEndAtMillis(t0 + min(61)))
    }

    @Test
    fun `progress is proportional`() {
        val timer = TimerState.start(t0, 100)
        assertEquals(0.25f, timer.progress(t0 + min(25)), 0.0001f)
    }

    @Test
    fun `zero planned minutes counts as reached immediately`() {
        val timer = TimerState.start(t0, 0)
        assertTrue(timer.isPlannedTimeReached(t0))
        assertEquals(1f, timer.progress(t0), 0.0001f)
    }

    @Test
    fun `hms formatting`() {
        assertEquals("00:00:00", TimeFormat.hms(0))
        assertEquals("01:02:03", TimeFormat.hms(sec(3723)))
        assertEquals("00:05:00", TimeFormat.hms(-min(5)))
        assertEquals("27:46:40", TimeFormat.hms(sec(100_000)))
    }

    @Test
    fun `short duration formatting`() {
        assertEquals("0m", TimeFormat.shortDuration(0))
        assertEquals("5m", TimeFormat.shortDuration(300))
        assertEquals("1h", TimeFormat.shortDuration(3600))
        assertEquals("1h 52m", TimeFormat.shortDuration(6720))
        assertEquals("1h 30m", TimeFormat.minutesLabel(90))
        assertEquals("2h", TimeFormat.minutesLabel(120))
    }
}
