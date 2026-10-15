package com.deeproot.app.core.timer

import java.util.Locale
import kotlin.math.abs

/**
 * Pure, timestamp-based study timer.
 *
 * Nothing "ticks" here. We persist only:
 *  - [plannedMillis]: the duration the student chose ("How long will this lecture take?")
 *  - [accumulatedMillis]: time banked from earlier running segments (before a pause)
 *  - [runningSinceMillis]: wall-clock start of the current running segment, or `null` while paused
 *
 * Studied time is always *derived* from `now`, so the value stays correct after the process is killed,
 * the screen is off, or the device sleeps. Wall-clock time (not `elapsedRealtime`) is used on purpose so the
 * timer also survives a reboot.
 */
data class TimerState(
    val plannedMillis: Long,
    val accumulatedMillis: Long = 0L,
    val runningSinceMillis: Long? = null,
) {
    init {
        require(plannedMillis >= 0L) { "plannedMillis must be >= 0" }
        require(accumulatedMillis >= 0L) { "accumulatedMillis must be >= 0" }
    }

    val isRunning: Boolean get() = runningSinceMillis != null
    val isPaused: Boolean get() = runningSinceMillis == null

    /** Total time studied, excluding pauses. Never negative, even if the clock jumped backwards. */
    fun studiedMillis(nowMillis: Long): Long {
        val running = runningSinceMillis?.let { (nowMillis - it).coerceAtLeast(0L) } ?: 0L
        return accumulatedMillis + running
    }

    fun studiedSeconds(nowMillis: Long): Long = studiedMillis(nowMillis) / 1000L

    /** Planned time left; negative values mean overtime. */
    fun remainingMillis(nowMillis: Long): Long = plannedMillis - studiedMillis(nowMillis)

    fun isPlannedTimeReached(nowMillis: Long): Boolean = studiedMillis(nowMillis) >= plannedMillis

    fun overtimeMillis(nowMillis: Long): Long = (-remainingMillis(nowMillis)).coerceAtLeast(0L)

    /** 0..1 progress towards the planned time. */
    fun progress(nowMillis: Long): Float =
        if (plannedMillis <= 0L) 1f
        else (studiedMillis(nowMillis).toDouble() / plannedMillis.toDouble()).coerceIn(0.0, 1.0).toFloat()

    /**
     * Wall-clock time at which the planned duration will be reached if the timer keeps running.
     * `null` while paused or once the planned time has already been reached.
     */
    fun plannedEndAtMillis(nowMillis: Long): Long? {
        if (!isRunning) return null
        val remaining = remainingMillis(nowMillis)
        return if (remaining <= 0L) null else nowMillis + remaining
    }

    /** Banks the running segment. Calling pause twice is a no-op. */
    fun pause(nowMillis: Long): TimerState =
        if (!isRunning) this else copy(accumulatedMillis = studiedMillis(nowMillis), runningSinceMillis = null)

    /** Starts a new running segment. Calling resume while running is a no-op. */
    fun resume(nowMillis: Long): TimerState = if (isRunning) this else copy(runningSinceMillis = nowMillis)

    companion object {
        const val MILLIS_PER_MINUTE: Long = 60_000L

        fun start(nowMillis: Long, plannedMinutes: Int): TimerState = TimerState(
            plannedMillis = plannedMinutes.coerceAtLeast(0) * MILLIS_PER_MINUTE,
            accumulatedMillis = 0L,
            runningSinceMillis = nowMillis,
        )
    }
}

/** Formatting helpers for timers and durations. Locale-independent digits on purpose. */
object TimeFormat {
    /** 3_723_000 → "01:02:03". Negative values are formatted by absolute value. */
    fun hms(millis: Long): String {
        val totalSeconds = abs(millis) / 1000L
        val h = totalSeconds / 3600L
        val m = (totalSeconds % 3600L) / 60L
        val s = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    /** 6720 s → "1h 52m", 300 s → "5m", 3600 s → "1h". */
    fun shortDuration(seconds: Long): String {
        val totalMinutes = seconds.coerceAtLeast(0L) / 60L
        val h = totalMinutes / 60L
        val m = totalMinutes % 60L
        return when {
            h > 0L && m > 0L -> "${h}h ${m}m"
            h > 0L -> "${h}h"
            else -> "${m}m"
        }
    }

    /** 90 → "1h 30m", 120 → "2h", 45 → "45m". */
    fun minutesLabel(minutes: Int): String = shortDuration(minutes.toLong() * 60L)
}
