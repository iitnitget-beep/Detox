package com.deeproot.app.data.calendar

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.deeproot.app.calendar.CalendarAuthorization
import com.deeproot.app.calendar.CalendarSyncWorker
import com.deeproot.app.calendar.GoogleAccountManager
import com.deeproot.app.core.calendar.CalendarDiff
import com.deeproot.app.core.calendar.CalendarEventInput
import com.deeproot.app.core.calendar.CalendarEventMapper
import com.deeproot.app.core.calendar.ExistingCalendarLecture
import com.deeproot.app.core.time.Dates
import com.deeproot.app.core.time.TimeSource
import com.deeproot.app.data.auth.AuthRepository
import com.deeproot.app.data.local.LectureDao
import com.deeproot.app.data.prefs.SettingsDataStore
import com.deeproot.app.data.remote.FunctionsDataSource
import com.deeproot.app.data.repository.LectureRepository
import com.deeproot.app.data.repository.SettingsRepository
import com.deeproot.app.data.sync.NetworkMonitor
import com.deeproot.app.data.sync.SyncManager
import com.google.firebase.functions.FirebaseFunctionsException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class CalendarInfo(
    val id: String,
    val name: String,
    val primary: Boolean,
    val color: String?,
)

sealed interface CalendarSyncOutcome {
    data class Success(
        val inserted: Int,
        val updated: Int,
        val deleted: Int,
        val keptCompleted: Int,
        val events: Int,
        val viaServer: Boolean,
    ) : CalendarSyncOutcome {
        val summary: String
            get() = buildString {
                append("$events events · ")
                append(listOf("$inserted new", "$updated updated", "$deleted removed").joinToString(", "))
                if (keptCompleted > 0) append(" · $keptCompleted completed kept")
            }
    }

    data class NeedsConsent(val pendingIntent: PendingIntent) : CalendarSyncOutcome
    data object NotConnected : CalendarSyncOutcome
    data object Offline : CalendarSyncOutcome
    data class Failed(val message: String) : CalendarSyncOutcome
}

/**
 * Google Calendar → lectures for the next [SYNC_DAYS] days. Preferred path: the `syncCalendar` Cloud Function (writes
 * Firestore, the snapshot listener updates Room). Fallback when the function is not deployed or unreachable: the same
 * diff computed on the device ([CalendarDiff]) and applied to Room, then uploaded by the normal sync.
 * Runs on app open (if stale), from "Sync now", and every 6 hours in the background ([CalendarSyncWorker]).
 */
@Singleton
class CalendarRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: CalendarApi,
    private val google: GoogleAccountManager,
    private val functions: FunctionsDataSource,
    private val lectures: LectureRepository,
    private val lectureDao: LectureDao,
    private val settings: SettingsRepository,
    private val prefs: SettingsDataStore,
    private val syncManager: SyncManager,
    private val network: NetworkMonitor,
    private val auth: AuthRepository,
    private val time: TimeSource,
) {
    private val mutex = Mutex()

    suspend fun listCalendars(accessToken: String): List<CalendarInfo> {
        val result = mutableListOf<CalendarInfo>()
        var pageToken: String? = null
        repeat(MAX_PAGES) {
            val page = api.calendarList(bearer(accessToken), pageToken = pageToken)
            page.items.mapTo(result) {
                CalendarInfo(
                    id = it.id,
                    name = it.summaryOverride ?: it.summary ?: it.id,
                    primary = it.primary,
                    color = it.backgroundColor,
                )
            }
            pageToken = page.nextPageToken ?: return result.sortedWith(compareByDescending<CalendarInfo> { it.primary }.thenBy { it.name.lowercase() })
        }
        return result.sortedWith(compareByDescending<CalendarInfo> { it.primary }.thenBy { it.name.lowercase() })
    }

    suspend fun selectCalendar(calendar: CalendarInfo) {
        settings.setCalendar(connected = true, calendarId = calendar.id, calendarName = calendar.name)
        schedulePeriodicSync()
    }

    /** Stops syncing. Already imported lectures stay (they are normal lectures now). */
    suspend fun disconnect() {
        settings.setCalendar(connected = false, calendarId = null, calendarName = null)
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
    }

    /** On app open: sync when connected and the last sync is older than 10 minutes. Silent (no UI). */
    suspend fun syncIfStale() {
        val s = settings.current()
        if (!s.calendarConnected || s.calendarId == null) return
        val last = prefs.currentFlags().calendarLastSyncAt
        if (last != null && time.nowMillis() - last < STALE_AFTER_MS) return
        syncNow(activity = null, source = "app-open")
    }

    suspend fun syncNow(activity: Activity?, source: String): CalendarSyncOutcome = mutex.withLock {
        val s = settings.current()
        val calendarId = s.calendarId
        if (!s.calendarConnected || calendarId == null) return@withLock CalendarSyncOutcome.NotConnected
        if (!network.isOnlineNow()) return@withLock CalendarSyncOutcome.Offline
        val outcome = when (val authorization = google.authorizeCalendar(activity)) {
            is CalendarAuthorization.Granted -> syncWithToken(authorization.accessToken, calendarId, retryAuth = true)
            is CalendarAuthorization.NeedsConsent -> CalendarSyncOutcome.NeedsConsent(authorization.pendingIntent)
            is CalendarAuthorization.Failed -> CalendarSyncOutcome.Failed(authorization.message)
        }
        record(outcome)
        Log.i(TAG, "Calendar sync ($source): $outcome")
        outcome
    }

    /** After the consent screen returned a token. */
    suspend fun syncWithFreshToken(accessToken: String): CalendarSyncOutcome = mutex.withLock {
        val calendarId = settings.current().calendarId ?: return@withLock CalendarSyncOutcome.NotConnected
        syncWithToken(accessToken, calendarId, retryAuth = false).also { record(it) }
    }

    fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private suspend fun syncWithToken(token: String, calendarId: String, retryAuth: Boolean): CalendarSyncOutcome {
        // Upload pending completions first so a server-side update never races with them.
        syncManager.pushDirty()
        val ignored = prefs.ignoredCalendarEvents()
        if (auth.currentUid != null) {
            try {
                val r = functions.syncCalendar(token, calendarId, time.zone().id, ignored)
                return CalendarSyncOutcome.Success(r.inserted, r.updated, r.deleted, r.keptCompleted, r.events, viaServer = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FirebaseFunctionsException) {
                when {
                    e.code == FirebaseFunctionsException.Code.UNAUTHENTICATED && retryAuth -> return retryWithNewToken(token, calendarId)
                    e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
                        return CalendarSyncOutcome.Failed(e.message ?: "Calendar not found. Pick a calendar again.")
                    e.code == FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                        return CalendarSyncOutcome.Failed(e.message ?: "No permission to read this calendar.")
                    FunctionsDataSource.isUnavailable(e) -> Log.i(TAG, "syncCalendar unavailable (${e.code}); syncing on the device")
                    else -> return CalendarSyncOutcome.Failed(e.message ?: "Calendar sync failed")
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncCalendar failed; syncing on the device", e)
            }
        }
        return deviceSync(token, calendarId, ignored, retryAuth)
    }

    private suspend fun retryWithNewToken(oldToken: String, calendarId: String): CalendarSyncOutcome {
        google.clearToken(oldToken)
        return when (val again = google.authorizeCalendar(null)) {
            is CalendarAuthorization.Granted -> syncWithToken(again.accessToken, calendarId, retryAuth = false)
            is CalendarAuthorization.NeedsConsent -> CalendarSyncOutcome.NeedsConsent(again.pendingIntent)
            is CalendarAuthorization.Failed -> CalendarSyncOutcome.Failed(again.message)
        }
    }

    private suspend fun deviceSync(token: String, calendarId: String, ignored: Set<String>, retryAuth: Boolean): CalendarSyncOutcome {
        val zone = time.zone()
        val today = time.today()
        val windowEnd = today.plusDays(SYNC_DAYS)
        return try {
            val events = fetchEvents(token, calendarId, today.atStartOfDay(zone).toOffsetDateTime(), windowEnd.atStartOfDay(zone).toOffsetDateTime())
            val inputs = events.mapNotNull { it.toInput(zone) }.filter { it.eventId !in ignored }
            val existing = lectureDao.getCalendarLectures().mapNotNull { row ->
                val eventId = row.calendarEventId ?: return@mapNotNull null
                val date = Dates.parseIsoOrNull(row.date) ?: return@mapNotNull null
                ExistingCalendarLecture(row.id, eventId, date, row.order, row.subject, row.title, row.plannedMinutes, row.completed)
            }
            val diff = CalendarDiff.compute(inputs, existing, today, windowEnd)
            lectures.applyCalendarDiff(diff)
            CalendarSyncOutcome.Success(
                inserted = diff.inserts.size,
                updated = diff.updates.size,
                deleted = diff.deletes.size,
                keptCompleted = diff.keptCompleted,
                events = inputs.size,
                viaServer = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> if (retryAuth) retryWithNewToken(token, calendarId) else CalendarSyncOutcome.Failed("Google rejected the access token. Reconnect the calendar.")
                403 -> CalendarSyncOutcome.Failed("No permission to read this calendar.")
                404 -> CalendarSyncOutcome.Failed("Calendar not found. Pick a calendar again.")
                else -> CalendarSyncOutcome.Failed("Google Calendar error ${e.code()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Device calendar sync failed", e)
            CalendarSyncOutcome.Failed(e.message ?: "Calendar sync failed")
        }
    }

    private suspend fun fetchEvents(token: String, calendarId: String, from: OffsetDateTime, to: OffsetDateTime): List<CalendarEvent> {
        val all = mutableListOf<CalendarEvent>()
        var pageToken: String? = null
        val timeMin = from.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val timeMax = to.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        repeat(MAX_PAGES) {
            val page = api.events(bearer(token), calendarId, timeMin = timeMin, timeMax = timeMax, pageToken = pageToken)
            all += page.items
            pageToken = page.nextPageToken ?: return all
        }
        return all
    }

    private suspend fun record(outcome: CalendarSyncOutcome) {
        val now = time.nowMillis()
        when (outcome) {
            is CalendarSyncOutcome.Success -> prefs.setCalendarSyncResult(now, outcome.summary, needsReauth = false)
            is CalendarSyncOutcome.NeedsConsent -> prefs.setCalendarSyncResult(now, "Google needs your permission again — tap Reconnect.", needsReauth = true)
            is CalendarSyncOutcome.Failed -> prefs.setCalendarSyncResult(now, outcome.message, needsReauth = false)
            CalendarSyncOutcome.NotConnected, CalendarSyncOutcome.Offline -> Unit
        }
    }

    private fun bearer(token: String) = "Bearer $token"

    companion object {
        private const val TAG = "CalendarRepository"
        const val SYNC_DAYS = 60L
        private const val MAX_PAGES = 20
        private const val STALE_AFTER_MS = 10 * 60 * 1000L
        private const val UNIQUE_PERIODIC = "calendar-sync"
    }
}

/** Google event → device-local date/time (pure logic in [CalendarEventMapper], unit tested). */
internal fun CalendarEvent.toInput(zone: ZoneId): CalendarEventInput? =
    CalendarEventMapper.toInput(id, status, summary, start?.date, start?.dateTime, end?.dateTime, zone)
