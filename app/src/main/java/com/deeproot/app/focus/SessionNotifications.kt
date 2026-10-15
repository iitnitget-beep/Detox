package com.deeproot.app.focus

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.deeproot.app.R
import com.deeproot.app.core.model.ActiveSession
import com.deeproot.app.core.timer.TimeFormat
import com.deeproot.app.notifications.AppIntents
import com.deeproot.app.notifications.NotificationChannels

/** Builds the ongoing study-session notification and the "planned time reached" alert. */
object SessionNotifications {

    private const val REQUEST_OPEN = 10
    private const val REQUEST_PAUSE = 11
    private const val REQUEST_RESUME = 12
    private const val REQUEST_FINISH = 13

    fun ongoing(context: Context, session: ActiveSession, nowMillis: Long, focusActive: Boolean): Notification {
        val timer = session.timer
        val studied = timer.studiedMillis(nowMillis)
        val remaining = timer.remainingMillis(nowMillis)
        val status = when {
            timer.isPaused -> "Paused · ${TimeFormat.hms(studied)} studied"
            remaining > 0 -> "${TimeFormat.hms(remaining)} left of ${TimeFormat.minutesLabel(session.plannedMinutes)}" +
                if (focusActive) " · Focus mode on" else ""
            else -> "Planned ${TimeFormat.minutesLabel(session.plannedMinutes)} reached · +${TimeFormat.hms(-remaining)}"
        }
        val builder = NotificationCompat.Builder(context, NotificationChannels.SESSION)
            .setSmallIcon(R.drawable.ic_stat_lecture)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle(session.displayName)
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(AppIntents.openPending(context, AppIntents.DESTINATION_FOCUS, REQUEST_OPEN))

        if (timer.isRunning) {
            // The system renders a live counting chronometer: "when" is the virtual start of the studied time.
            builder.setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(nowMillis - studied)
                .addAction(0, "Pause", actionIntent(context, SessionActionReceiver.ACTION_PAUSE, REQUEST_PAUSE))
        } else {
            builder.setUsesChronometer(false)
                .setShowWhen(false)
                .addAction(0, "Resume", actionIntent(context, SessionActionReceiver.ACTION_RESUME, REQUEST_RESUME))
        }
        builder.addAction(0, "Finish", actionIntent(context, SessionActionReceiver.ACTION_FINISH, REQUEST_FINISH))
        return builder.build()
    }

    fun plannedEnd(context: Context, session: ActiveSession): Notification =
        NotificationCompat.Builder(context, NotificationChannels.SESSION_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_lecture)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle("Planned time reached")
            .setContentText("${TimeFormat.minutesLabel(session.plannedMinutes)} of ${session.displayName}. Focus mode is off. Tap Finish when you're done.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "You planned ${TimeFormat.minutesLabel(session.plannedMinutes)} for ${session.displayName}. " +
                        "Focus mode is now off. The timer keeps counting until you tap Finish.",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(AppIntents.openPending(context, AppIntents.DESTINATION_FOCUS, REQUEST_OPEN))
            .addAction(0, "Finish", actionIntent(context, SessionActionReceiver.ACTION_FINISH, REQUEST_FINISH))
            .build()

    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, SessionActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
