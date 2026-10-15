package com.deeproot.app.focus

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.deeproot.app.data.repository.FinishResult
import com.deeproot.app.data.repository.SessionRepository
import com.deeproot.app.data.repository.StartResult
import com.deeproot.app.notifications.NotificationChannels
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for the study timer used by the UI, the notification actions and the boot receiver.
 * Persists the change first (Room), then makes sure the foreground service reflects it.
 */
@Singleton
class StudySessionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionRepository,
) {
    /** Must be called while the app is in the foreground (Android forbids starting this service from the background). */
    suspend fun start(lectureId: String, plannedMinutes: Int): StartResult {
        val result = sessions.start(lectureId, plannedMinutes)
        if (result is StartResult.Started) StudySessionService.start(context)
        return result
    }

    suspend fun pause() {
        sessions.pause()
    }

    suspend fun resume() {
        sessions.resume()
    }

    /** Ends the session; the service notices that no session is active and stops itself. */
    suspend fun finish(markCompleted: Boolean): FinishResult? {
        val result = sessions.finish(markCompleted)
        context.getSystemService(NotificationManager::class.java)?.cancel(NotificationChannels.PLANNED_END_NOTIFICATION_ID)
        return result
    }

    /** Restarts the service for a session that survived process death (app opened, boot completed). */
    suspend fun ensureServiceRunning() {
        if (sessions.current() == null) return
        try {
            StudySessionService.start(context)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (a subclass) when not allowed from the background.
            Log.w("StudySessionController", "Could not start the session service now", e)
        }
    }
}
