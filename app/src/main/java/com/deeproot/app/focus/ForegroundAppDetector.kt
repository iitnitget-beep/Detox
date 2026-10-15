package com.deeproot.app.focus

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Finds the app currently in the foreground from UsageStatsManager events (needs Usage access).
 * Keeps the last known value between polls because events only arrive when something changes.
 */
class ForegroundAppDetector(context: Context) {

    private val usageStats = context.getSystemService(UsageStatsManager::class.java)
    private var lastQueryEnd = 0L
    private var current: String? = null

    /** Returns the package of the most recently resumed activity, or null when unknown. */
    fun foregroundPackage(nowMillis: Long): String? {
        val manager = usageStats ?: return null
        val start = if (lastQueryEnd == 0L) nowMillis - BOOTSTRAP_WINDOW_MS else lastQueryEnd - OVERLAP_MS
        val events = try {
            manager.queryEvents(start, nowMillis)
        } catch (e: RuntimeException) {
            // SecurityException without Usage access; some ROMs throw other runtime exceptions here.
            return null
        } ?: return current
        lastQueryEnd = nowMillis
        val event = UsageEvents.Event()
        var latestTime = Long.MIN_VALUE
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp >= latestTime) {
                latestTime = event.timeStamp
                current = event.packageName
            }
        }
        return current
    }

    fun reset() {
        lastQueryEnd = 0L
        current = null
    }

    private companion object {
        /** On the first poll look back far enough to find the app that is open right now. */
        const val BOOTSTRAP_WINDOW_MS = 6 * 60 * 60 * 1000L
        /** Small overlap so events written slightly late are not missed. */
        const val OVERLAP_MS = 2_000L
    }
}
