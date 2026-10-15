package com.deeproot.app.focus

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.deeproot.app.R
import com.deeproot.app.core.focus.FocusPolicy
import com.deeproot.app.core.model.ActiveSession
import com.deeproot.app.core.model.AppSettings
import com.deeproot.app.core.time.TimeSource
import com.deeproot.app.core.timer.TimeFormat
import com.deeproot.app.data.prefs.SettingsDataStore
import com.deeproot.app.data.repository.SessionRepository
import com.deeproot.app.notifications.NotificationChannels
import com.deeproot.app.permissions.PermissionChecker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Foreground service that exists while a study session is active.
 *
 * - Ongoing notification with a live chronometer (rebuilt every minute and on every state change).
 * - Posts "Planned time reached" once; Focus mode turns off at that point while the timer keeps counting overtime.
 * - Focus mode: polls UsageStatsManager (~700 ms while the screen is on) and covers any app that is not this app, an
 *   allowed app, or the dialer / emergency / settings / launcher with [FocusOverlay].
 * - START_STICKY + timestamps in Room: if the system kills the process, the service restarts and continues exactly
 *   where it was. The session is paused on ACTION_SHUTDOWN so a reboot is not counted as study time.
 */
@AndroidEntryPoint
class StudySessionService : Service() {

    @Inject lateinit var sessions: SessionRepository
    @Inject lateinit var settingsStore: SettingsDataStore
    @Inject lateinit var permissions: PermissionChecker
    @Inject lateinit var time: TimeSource

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var focusJob: Job? = null
    private var tickerJob: Job? = null
    private var inForeground = false

    private lateinit var overlay: FocusOverlay
    private lateinit var detector: ForegroundAppDetector
    private var deviceAllowed: Set<String> = emptySet()

    @Volatile private var latestSession: ActiveSession? = null
    @Volatile private var latestSettings: AppSettings = AppSettings()

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Bank the running time so the time the phone is off is not counted as study time.
            runBlocking { withTimeoutOrNull(2_000) { sessions.pause() } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        overlay = FocusOverlay(this)
        detector = ForegroundAppDetector(this)
        deviceAllowed = DeviceAllowedApps.resolve(this)
        ContextCompat.registerReceiver(
            this,
            shutdownReceiver,
            IntentFilter(Intent.ACTION_SHUTDOWN),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must be followed by startForeground() within a few seconds.
        val session = latestSession ?: runBlocking { withTimeoutOrNull(1_500) { sessions.current() } }
        if (session == null) {
            startForegroundCompat(placeholderNotification())
            stopSelfSafely()
            return START_NOT_STICKY
        }
        latestSession = session
        startForegroundCompat(SessionNotifications.ongoing(this, session, time.nowMillis(), focusActive = false))
        if (observeJob == null) observeJob = scope.launch { observeSession() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlay.hide()
        scope.cancel()
        try {
            unregisterReceiver(shutdownReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver was not registered", e)
        }
        super.onDestroy()
    }

    private suspend fun observeSession() {
        // permissions.state is refreshed by MainActivity on every resume, so re-granting a permission restarts Focus.
        combine(sessions.active, settingsStore.settings, permissions.state) { session, settings, _ -> session to settings }
            .collect { (session, settings) ->
                latestSettings = settings
                if (session == null) {
                    latestSession = null
                    stopSelfSafely()
                    return@collect
                }
                latestSession = session
                updateNotification(session)
                restartTicker()
                updateFocus(session, settings)
            }
    }

    /** Refreshes the notification every minute and fires the planned-end alert exactly once. */
    private fun restartTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                val session = latestSession ?: break
                val now = time.nowMillis()
                if (!session.plannedEndNotified && session.timer.isPlannedTimeReached(now)) {
                    onPlannedTimeReached(session)
                    break // the session row changed; observeSession restarts the ticker
                }
                updateNotification(session)
                val remaining = session.timer.remainingMillis(now)
                val untilEnd = if (session.timer.isRunning && remaining > 0) remaining else Long.MAX_VALUE
                delay(minOf(60_000L - (now % 60_000L), untilEnd).coerceAtLeast(250L))
            }
        }
    }

    private suspend fun onPlannedTimeReached(session: ActiveSession) {
        overlay.hide()
        sessions.markPlannedEndNotified()
        if (permissions.hasNotificationPermission()) {
            getSystemService(NotificationManager::class.java)
                ?.notify(NotificationChannels.PLANNED_END_NOTIFICATION_ID, SessionNotifications.plannedEnd(this, session))
        }
    }

    private fun focusShouldRun(session: ActiveSession, settings: AppSettings, focusReady: Boolean): Boolean =
        settings.focusModeEnabled &&
            focusReady &&
            !session.plannedEndNotified &&
            !session.timer.isPlannedTimeReached(time.nowMillis())

    private fun updateFocus(session: ActiveSession, settings: AppSettings) {
        if (!focusShouldRun(session, settings, permissions.refresh().focusReady)) {
            focusJob?.cancel()
            focusJob = null
            overlay.hide()
            return
        }
        if (focusJob?.isActive == true) return
        detector.reset()
        focusJob = scope.launch { focusLoop() }
    }

    private suspend fun focusLoop() {
        val power = getSystemService(PowerManager::class.java)
        var lastPermissionCheck = 0L
        var focusReady = true
        while (currentCoroutineContext().isActive) {
            val session = latestSession ?: break
            val settings = latestSettings
            val elapsed = SystemClock.elapsedRealtime()
            if (elapsed - lastPermissionCheck >= PERMISSION_RECHECK_MS) {
                // Usage access or overlay can be revoked at any time; without them we must never keep a cover up.
                focusReady = permissions.refresh().focusReady
                lastPermissionCheck = elapsed
            }
            if (!focusShouldRun(session, settings, focusReady)) break
            if (power?.isInteractive == false) {
                overlay.hide()
                detector.reset()
                delay(SCREEN_OFF_POLL_MS)
                continue
            }
            val now = time.nowMillis()
            val foreground = withContext(Dispatchers.Default) { detector.foregroundPackage(now) }
            val block = FocusPolicy.shouldBlock(
                foregroundPackage = foreground,
                ownPackage = packageName,
                userAllowed = settings.allowedApps,
                deviceAllowed = deviceAllowed,
            )
            if (block && permissions.hasUsageAccess()) {
                val remaining = session.timer.remainingMillis(now)
                overlay.show(
                    lectureName = session.displayName,
                    remainingText = "${TimeFormat.hms(remaining)} left of ${TimeFormat.minutesLabel(session.plannedMinutes)}",
                )
            } else {
                overlay.hide()
            }
            delay(POLL_MS)
        }
        overlay.hide()
        focusJob = null
        latestSession?.let { updateNotification(it) }
    }

    private fun focusActive(session: ActiveSession): Boolean = focusJob?.isActive == true && !session.plannedEndNotified

    private fun updateNotification(session: ActiveSession) {
        val notification = SessionNotifications.ongoing(this, session, time.nowMillis(), focusActive(session))
        getSystemService(NotificationManager::class.java)?.notify(NotificationChannels.SESSION_NOTIFICATION_ID, notification)
    }

    /** Only used to satisfy the startForeground() contract right before stopping (no active session). */
    private fun placeholderNotification(): Notification =
        NotificationCompat.Builder(this, NotificationChannels.SESSION)
            .setSmallIcon(R.drawable.ic_stat_lecture)
            .setContentTitle("Study session")
            .setSilent(true)
            .build()

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NotificationChannels.SESSION_NOTIFICATION_ID, notification, type)
            inForeground = true
        } catch (e: RuntimeException) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun stopSelfSafely() {
        overlay.hide()
        focusJob?.cancel()
        focusJob = null
        tickerJob?.cancel()
        tickerJob = null
        if (inForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
        }
        stopSelf()
    }

    companion object {
        private const val TAG = "StudySessionService"
        private const val POLL_MS = 700L
        private const val SCREEN_OFF_POLL_MS = 3_000L
        private const val PERMISSION_RECHECK_MS = 5_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, StudySessionService::class.java))
        }
    }
}
