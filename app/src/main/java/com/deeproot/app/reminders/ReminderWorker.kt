package com.deeproot.app.reminders

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs at each reminder slot (one-time work chained to the next slot) and hourly as a safety net. Shows at most one
 * reminder per slot, only inside the reminder window and only if something is pending today or in the backlog.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val notifier: ReminderNotifier,
    private val scheduler: ReminderScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val source = inputData.getString(KEY_SOURCE) ?: SOURCE_SLOT
        notifier.evaluateAndNotify(source)
        // Keep the chain going: the next one-time run is aligned to the next slot.
        scheduler.scheduleNextSlot(fromWorker = source == SOURCE_SLOT)
        return Result.success()
    }

    companion object {
        const val KEY_SOURCE = "source"
        const val SOURCE_SLOT = "slot"
        const val SOURCE_SAFETY_NET = "safety-net"
    }
}
