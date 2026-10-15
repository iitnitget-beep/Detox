package com.deeproot.app.reminders

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.deeproot.app.core.model.AppSettings
import com.deeproot.app.core.reminder.ReminderRules
import com.deeproot.app.core.time.TimeSource
import com.deeproot.app.data.prefs.SettingsDataStore
import com.deeproot.app.di.ApplicationScope
import com.deeproot.app.permissions.PermissionChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules reminders with WorkManager:
 * - a one-time job at the next slot (08:00, 10:00, … by default), re-chained after every run;
 * - an hourly periodic safety net that re-creates the chain if it was lost.
 * Both are cancelled while reminders are off, onboarding is unfinished or notifications are not allowed.
 *
 * Doze note: WorkManager (JobScheduler) work is deferred to maintenance windows while the phone is idle, so a reminder
 * can arrive late. Exact alarms would need SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM, which Android 14 denies by default
 * and Play reserves for alarm-clock/calendar apps — hence: battery-optimisation exemption (asked in onboarding) and
 * the high-priority FCM fallback from the scheduledReminder Cloud Function.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val prefs: SettingsDataStore,
    private val permissions: PermissionChecker,
    private val time: TimeSource,
) {
    private val started = AtomicBoolean(false)
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    private data class Key(
        val active: Boolean,
        val interval: Int,
        val start: Int,
        val end: Int,
    )

    /** Called from Application.onCreate: keeps the schedule in sync with settings and permission changes. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var first = true
            combine(prefs.settings, permissions.state) { settings, perms -> keyOf(settings, perms.notifications) }
                .distinctUntilChanged()
                .collect { key ->
                    if (key.active) {
                        ensurePeriodic(if (first) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE)
                        scheduleNextSlot(fromWorker = false)
                    } else {
                        cancelAll()
                    }
                    first = false
                }
        }
    }

    /** After reboot / time-zone change: rebuild everything from scratch. */
    suspend fun reschedule() {
        val settings = prefs.current()
        if (keyOf(settings, permissions.hasNotificationPermission()).active) {
            ensurePeriodic(ExistingPeriodicWorkPolicy.UPDATE)
            scheduleNextSlot(fromWorker = false)
        } else {
            cancelAll()
        }
    }

    suspend fun scheduleNextSlot(fromWorker: Boolean) {
        val settings = prefs.current()
        if (!keyOf(settings, permissions.hasNotificationPermission()).active) {
            cancelAll()
            return
        }
        val now = time.nowLocal()
        val next = ReminderRules.nextSlotAfter(now, settings.reminderSettings) ?: return
        val delay = Duration.between(now, next).toMillis().coerceAtLeast(MIN_DELAY_MS)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY_SOURCE to ReminderWorker.SOURCE_SLOT))
            .addTag(TAG_REMINDERS)
            .build()
        // From the running worker: append (REPLACE would cancel the worker itself). From the app: replace the old
        // timing, e.g. after the interval was changed.
        val policy = if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        workManager.enqueueUniqueWork(UNIQUE_SLOT, policy, request)
        Log.i(TAG, "Next reminder check at $next (in ${delay / 60_000} min)")
    }

    fun nextSlotTime(settings: AppSettings): LocalDateTime? =
        if (settings.remindersEnabled) ReminderRules.nextSlotAfter(time.nowLocal(), settings.reminderSettings) else null

    private fun ensurePeriodic(policy: ExistingPeriodicWorkPolicy) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.HOURS)
            .setInputData(workDataOf(ReminderWorker.KEY_SOURCE to ReminderWorker.SOURCE_SAFETY_NET))
            .addTag(TAG_REMINDERS)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, policy, request)
    }

    private fun cancelAll() {
        workManager.cancelUniqueWork(UNIQUE_SLOT)
        workManager.cancelUniqueWork(UNIQUE_PERIODIC)
    }

    private fun keyOf(settings: AppSettings, notificationsAllowed: Boolean) = Key(
        active = settings.onboardingComplete && settings.remindersEnabled && notificationsAllowed,
        interval = settings.reminderIntervalHours,
        start = settings.reminderStartHour,
        end = settings.reminderEndHour,
    )

    private companion object {
        const val TAG = "ReminderScheduler"
        const val TAG_REMINDERS = "reminders"
        const val UNIQUE_SLOT = "reminder-next-slot"
        const val UNIQUE_PERIODIC = "reminder-safety-net"
        const val MIN_DELAY_MS = 30_000L
    }
}
