package com.deeproot.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Notification channels (created at app start; Android ignores re-creation of existing channels). */
object NotificationChannels {
    /** Ongoing study-session notification with the live timer. Silent. */
    const val SESSION = "study_session"

    /** "Planned time reached" and similar alerts during a session. */
    const val SESSION_ALERTS = "session_alerts"

    /** Pending-lecture reminders (local WorkManager and FCM fallback). */
    const val REMINDERS = "reminders"

    const val SESSION_NOTIFICATION_ID = 1001
    const val PLANNED_END_NOTIFICATION_ID = 1002
    const val REMINDER_NOTIFICATION_ID = 2001

    fun createAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val session = NotificationChannel(SESSION, "Study session", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows the running study timer"
            setShowBadge(false)
        }
        val alerts = NotificationChannel(SESSION_ALERTS, "Study session alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Tells you when the planned study time is reached"
        }
        val reminders = NotificationChannel(REMINDERS, "Lecture reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Reminds you about pending lectures and backlog"
        }
        manager.createNotificationChannels(listOf(session, alerts, reminders))
    }
}
