package com.deeproot.app.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Uploads pending local changes once the device is online. Unique, so repeated enqueues collapse into one run. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: SyncManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (runAttemptCount >= MAX_ATTEMPTS) return Result.failure()
        var outcome = syncManager.pushDirty()
        if (outcome == PushOutcome.NOT_SIGNED_IN && syncManager.ensureSignedIn()) {
            outcome = syncManager.pushDirty()
        }
        return when (outcome) {
            PushOutcome.DONE -> Result.success()
            PushOutcome.RETRY, PushOutcome.OFFLINE, PushOutcome.NOT_SIGNED_IN -> Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "sync-push"
        private const val MAX_ATTEMPTS = 20

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // KEEP: one pending run uploads everything that is dirty at that time. A change made while a run is in
            // progress is either picked up by its next round or makes it return retry() (rows still dirty).
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
