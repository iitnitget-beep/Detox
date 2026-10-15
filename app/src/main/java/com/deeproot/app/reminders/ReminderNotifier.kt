package com.deeproot.app.reminders

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.deeproot.app.R
import com.deeproot.app.core.reminder.PendingLecture
import com.deeproot.app.core.reminder.ReminderDecision
import com.deeproot.app.core.reminder.ReminderRules
import com.deeproot.app.core.reminder.SkipReason
import com.deeproot.app.core.time.Dates
import com.deeproot.app.core.time.TimeSource
import com.deeproot.app.data.auth.AuthRepository
import com.deeproot.app.data.local.ActiveSessionDao
import com.deeproot.app.data.prefs.SettingsDataStore
import com.deeproot.app.data.remote.FirestoreDataSource
import com.deeproot.app.data.repository.LectureRepository
import com.deeproot.app.notifications.AppIntents
import com.deeproot.app.notifications.NotificationChannels
import com.deeproot.app.permissions.PermissionChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides (with [ReminderRules]) and shows the "N lectures pending. Next: …" notification. Shared by the local
 * WorkManager job and the FCM fallback, so both obey the same window and the same one-per-slot rule.
 */
@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SettingsDataStore,
    private val lectures: LectureRepository,
    private val activeSessionDao: ActiveSessionDao,
    private val permissions: PermissionChecker,
    private val auth: AuthRepository,
    private val remote: FirestoreDataSource,
    private val time: TimeSource,
) {
    private val mutex = Mutex()

    suspend fun evaluateAndNotify(source: String): ReminderDecision = mutex.withLock {
        val settings = prefs.current()
        if (!settings.onboardingComplete || !permissions.hasNotificationPermission()) {
            return@withLock ReminderDecision.Skip(SkipReason.DISABLED)
        }
        if (activeSessionDao.get() != null) {
            // Already studying: no nagging during a session.
            return@withLock ReminderDecision.Skip(SkipReason.ALREADY_REMINDED_THIS_SLOT)
        }
        val now = time.nowLocal()
        val pending = pendingLectures()
        val last = prefs.currentFlags().lastReminderAt?.let { Dates.localDateTimeOf(it, time.zone()) }
        val decision = ReminderRules.decide(now, settings.reminderSettings, pending, last)
        if (decision is ReminderDecision.Notify) {
            show(decision.message, pending)
            recordShown()
        }
        Log.i(TAG, "Reminder check ($source): $decision")
        decision
    }

    /** "Send test reminder" in Settings: shows the current message now, ignoring the window and slot. */
    suspend fun showTest(): Boolean {
        if (!permissions.hasNotificationPermission()) return false
        val pending = pendingLectures()
        val ordered = ReminderRules.orderForReminder(pending, time.today())
        val message = ReminderRules.buildMessage(ordered) ?: "No lectures pending — you're all caught up 🎉"
        show(message, pending)
        return true
    }

    private suspend fun pendingLectures(): List<PendingLecture> =
        lectures.pendingUpTo(time.today()).map { PendingLecture(it.date, it.order, it.subject, it.title) }

    private suspend fun recordShown() {
        val nowMillis = time.nowMillis()
        prefs.setLastReminderAt(nowMillis)
        // Lets the scheduled Cloud Function skip its push for this slot (best effort, offline is fine).
        auth.currentUid?.let { uid ->
            runCatching { remote.setLastLocalReminder(uid, nowMillis) }.onFailure { Log.w(TAG, "Could not record reminder", it) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun show(message: String, pending: List<PendingLecture>) {
        val ordered = ReminderRules.orderForReminder(pending, time.today())
        val details = ordered.take(5).joinToString("\n") { "• ${it.subject} – ${it.title}" } +
            if (ordered.size > 5) "\n…and ${ordered.size - 5} more" else ""
        val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_lecture)
            .setColor(ContextCompat.getColor(context, R.color.brand))
            .setContentTitle("Lectures pending")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (ordered.isEmpty()) message else "$message\n\n$details"))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(AppIntents.openPending(context, AppIntents.DESTINATION_TODAY, REQUEST_OPEN_TODAY))
            .build()
        if (permissions.hasNotificationPermission()) {
            NotificationManagerCompat.from(context).notify(NotificationChannels.REMINDER_NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val TAG = "ReminderNotifier"
        const val REQUEST_OPEN_TODAY = 30
    }
}
