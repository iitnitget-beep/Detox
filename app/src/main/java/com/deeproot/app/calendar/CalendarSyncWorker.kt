package com.deeproot.app.calendar

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.deeproot.app.data.calendar.CalendarRepository
import com.deeproot.app.data.calendar.CalendarSyncOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Background calendar sync every 6 hours (network required). Never shows UI: missing consent is surfaced in Settings. */
@HiltWorker
class CalendarSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: CalendarRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (repository.syncNow(activity = null, source = "worker")) {
        is CalendarSyncOutcome.Success,
        is CalendarSyncOutcome.NeedsConsent,
        CalendarSyncOutcome.NotConnected,
        -> Result.success()
        CalendarSyncOutcome.Offline -> Result.retry()
        is CalendarSyncOutcome.Failed -> if (runAttemptCount < 3) Result.retry() else Result.success()
    }
}
