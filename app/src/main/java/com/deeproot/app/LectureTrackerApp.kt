package com.deeproot.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.deeproot.app.data.sync.SyncManager
import com.deeproot.app.notifications.NotificationChannels
import com.deeproot.app.reminders.ReminderScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class LectureTrackerApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncManager: SyncManager

    @Inject
    lateinit var reminderScheduler: ReminderScheduler

    /** WorkManager's default initializer is removed in the manifest so workers get Hilt injection. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
        // Seeds the bundled plan into Room (first launch) and starts Firestore sync.
        syncManager.start()
        // Keeps the WorkManager reminder schedule in sync with settings/permissions (only active after onboarding).
        reminderScheduler.start()
    }
}
