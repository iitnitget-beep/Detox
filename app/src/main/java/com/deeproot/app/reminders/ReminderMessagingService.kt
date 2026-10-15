package com.deeproot.app.reminders

import android.util.Log
import com.deeproot.app.data.sync.SyncManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Receives the data-only, high-priority push sent by the `scheduledReminder` Cloud Function when the local WorkManager
 * reminder did not run for a slot (e.g. deep Doze). The local rules decide again with local data, so a reminder that
 * was already shown for this slot is never repeated.
 */
@AndroidEntryPoint
class ReminderMessagingService : FirebaseMessagingService() {

    @Inject lateinit var notifier: ReminderNotifier
    @Inject lateinit var syncManager: SyncManager

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != TYPE_REMINDER) return
        // onMessageReceived runs on a background thread and may take a few seconds.
        val decision = runBlocking { withTimeoutOrNull(8_000) { notifier.evaluateAndNotify(source = "fcm") } }
        Log.i(TAG, "FCM reminder → $decision")
    }

    override fun onNewToken(token: String) {
        syncManager.onNewFcmToken(token)
    }

    private companion object {
        const val TAG = "ReminderMessaging"
        const val TYPE_REMINDER = "reminder"
    }
}
