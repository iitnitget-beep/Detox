package com.deeproot.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.deeproot.app.core.model.AppSettings
import com.deeproot.app.core.reminder.ReminderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Local-only bookkeeping that never leaves the device (except where noted). */
data class LocalFlags(
    val lastReminderAt: Long? = null,
    val planSeedVersion: Int = 0,
    val planSeededCount: Int = 0,
    val remoteSeededUid: String? = null,
    val lastSyncedUid: String? = null,
    val lastSyncAt: Long? = null,
    val settingsDirty: Boolean = false,
    val fcmToken: String? = null,
    val calendarLastSyncAt: Long? = null,
    val calendarLastSyncMessage: String? = null,
    val calendarNeedsReauth: Boolean = false,
)

/**
 * DataStore-backed preferences. [AppSettings] are the user's choices (mirrored to Firestore);
 * [LocalFlags] are sync/reminder bookkeeping.
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store: DataStore<Preferences> get() = context.appDataStore

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: Flow<AppSettings> = data.map { it.toSettings() }.distinctUntilChanged()

    val flags: Flow<LocalFlags> = data.map { it.toFlags() }.distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun currentFlags(): LocalFlags = flags.first()

    /** Applies a user change: bumps `updatedAt` and marks the settings for upload. */
    suspend fun update(nowMillis: Long, transform: (AppSettings) -> AppSettings): AppSettings {
        var result = AppSettings()
        store.edit { prefs ->
            val updated = transform(prefs.toSettings()).sanitized().copy(updatedAt = nowMillis)
            prefs.writeSettings(updated)
            prefs[Keys.SETTINGS_DIRTY] = true
            result = updated
        }
        return result
    }

    /** Applies settings downloaded from Firestore (newer than local) without marking them dirty. */
    suspend fun applyRemote(remote: RemoteSettings) {
        store.edit { prefs ->
            val local = prefs.toSettings()
            val merged = local.copy(
                remindersEnabled = remote.remindersEnabled,
                reminderIntervalHours = remote.reminderIntervalHours,
                reminderStartHour = remote.reminderStartHour,
                reminderEndHour = remote.reminderEndHour,
                focusModeEnabled = remote.focusModeEnabled,
                allowedApps = remote.allowedApps,
                allowedAppsInitialized = local.allowedAppsInitialized || remote.allowedApps.isNotEmpty(),
                calendarId = remote.calendarId ?: local.calendarId,
                updatedAt = remote.updatedAt,
            ).sanitized()
            prefs.writeSettings(merged)
            prefs[Keys.SETTINGS_DIRTY] = false
        }
    }

    suspend fun markSettingsClean(updatedAt: Long) {
        store.edit { prefs ->
            // Only clear if nothing changed while the upload was in flight.
            if ((prefs[Keys.SETTINGS_UPDATED] ?: 0L) == updatedAt) prefs[Keys.SETTINGS_DIRTY] = false
        }
    }

    suspend fun markSettingsDirty() {
        store.edit { it[Keys.SETTINGS_DIRTY] = true }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        store.edit { it[Keys.ONBOARDING] = complete }
    }

    suspend fun setLastReminderAt(millis: Long) {
        store.edit { it[Keys.LAST_REMINDER] = millis }
    }

    suspend fun setPlanSeeded(version: Int, count: Int) {
        store.edit {
            it[Keys.PLAN_SEED_VERSION] = version
            it[Keys.PLAN_SEEDED_COUNT] = count
        }
    }

    suspend fun setRemoteSeededUid(uid: String?) {
        store.edit { if (uid == null) it.remove(Keys.REMOTE_SEEDED_UID) else it[Keys.REMOTE_SEEDED_UID] = uid }
    }

    suspend fun setLastSynced(uid: String, atMillis: Long) {
        store.edit {
            it[Keys.LAST_SYNCED_UID] = uid
            it[Keys.LAST_SYNC_AT] = atMillis
        }
    }

    suspend fun setFcmToken(token: String?) {
        store.edit { if (token == null) it.remove(Keys.FCM_TOKEN) else it[Keys.FCM_TOKEN] = token }
    }

    suspend fun setCalendarSyncResult(atMillis: Long, message: String, needsReauth: Boolean) {
        store.edit {
            it[Keys.CAL_LAST_SYNC_AT] = atMillis
            it[Keys.CAL_LAST_SYNC_MSG] = message
            it[Keys.CAL_NEEDS_REAUTH] = needsReauth
        }
    }

    /** Calendar events whose imported lecture the user deleted: never re-imported by calendar sync. */
    suspend fun ignoredCalendarEvents(): Set<String> = data.first()[Keys.CAL_IGNORED] ?: emptySet()

    suspend fun addIgnoredCalendarEvents(eventIds: Collection<String>) {
        if (eventIds.isEmpty()) return
        store.edit { it[Keys.CAL_IGNORED] = (it[Keys.CAL_IGNORED] ?: emptySet()) + eventIds }
    }

    suspend fun removeIgnoredCalendarEvents(eventIds: Collection<String>) {
        if (eventIds.isEmpty()) return
        store.edit { it[Keys.CAL_IGNORED] = (it[Keys.CAL_IGNORED] ?: emptySet()) - eventIds.toSet() }
    }

    suspend fun setCalendar(connected: Boolean, calendarId: String?, calendarName: String?, nowMillis: Long) {
        store.edit {
            it[Keys.CAL_CONNECTED] = connected
            if (calendarId == null) it.remove(Keys.CAL_ID) else it[Keys.CAL_ID] = calendarId
            if (calendarName == null) it.remove(Keys.CAL_NAME) else it[Keys.CAL_NAME] = calendarName
            if (!connected) {
                it.remove(Keys.CAL_LAST_SYNC_AT)
                it.remove(Keys.CAL_LAST_SYNC_MSG)
                it[Keys.CAL_NEEDS_REAUTH] = false
            }
            it[Keys.SETTINGS_UPDATED] = nowMillis
            it[Keys.SETTINGS_DIRTY] = true
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            onboardingComplete = this[Keys.ONBOARDING] ?: defaults.onboardingComplete,
            remindersEnabled = this[Keys.REMINDERS_ENABLED] ?: defaults.remindersEnabled,
            reminderIntervalHours = this[Keys.INTERVAL] ?: defaults.reminderIntervalHours,
            reminderStartHour = this[Keys.START] ?: defaults.reminderStartHour,
            reminderEndHour = this[Keys.END] ?: defaults.reminderEndHour,
            focusModeEnabled = this[Keys.FOCUS] ?: defaults.focusModeEnabled,
            allowedApps = this[Keys.ALLOWED] ?: defaults.allowedApps,
            allowedAppsInitialized = this[Keys.ALLOWED_INIT] ?: defaults.allowedAppsInitialized,
            calendarConnected = this[Keys.CAL_CONNECTED] ?: defaults.calendarConnected,
            calendarId = this[Keys.CAL_ID],
            calendarName = this[Keys.CAL_NAME],
            updatedAt = this[Keys.SETTINGS_UPDATED] ?: defaults.updatedAt,
        )
    }

    private fun Preferences.toFlags(): LocalFlags = LocalFlags(
        lastReminderAt = this[Keys.LAST_REMINDER],
        planSeedVersion = this[Keys.PLAN_SEED_VERSION] ?: 0,
        planSeededCount = this[Keys.PLAN_SEEDED_COUNT] ?: 0,
        remoteSeededUid = this[Keys.REMOTE_SEEDED_UID],
        lastSyncedUid = this[Keys.LAST_SYNCED_UID],
        lastSyncAt = this[Keys.LAST_SYNC_AT],
        settingsDirty = this[Keys.SETTINGS_DIRTY] ?: false,
        fcmToken = this[Keys.FCM_TOKEN],
        calendarLastSyncAt = this[Keys.CAL_LAST_SYNC_AT],
        calendarLastSyncMessage = this[Keys.CAL_LAST_SYNC_MSG],
        calendarNeedsReauth = this[Keys.CAL_NEEDS_REAUTH] ?: false,
    )

    private fun MutablePreferences.writeSettings(s: AppSettings) {
        this[Keys.ONBOARDING] = s.onboardingComplete
        this[Keys.REMINDERS_ENABLED] = s.remindersEnabled
        this[Keys.INTERVAL] = s.reminderIntervalHours
        this[Keys.START] = s.reminderStartHour
        this[Keys.END] = s.reminderEndHour
        this[Keys.FOCUS] = s.focusModeEnabled
        this[Keys.ALLOWED] = s.allowedApps
        this[Keys.ALLOWED_INIT] = s.allowedAppsInitialized
        this[Keys.CAL_CONNECTED] = s.calendarConnected
        if (s.calendarId == null) remove(Keys.CAL_ID) else this[Keys.CAL_ID] = s.calendarId
        if (s.calendarName == null) remove(Keys.CAL_NAME) else this[Keys.CAL_NAME] = s.calendarName
        this[Keys.SETTINGS_UPDATED] = s.updatedAt
    }

    private object Keys {
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
        val REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        val INTERVAL = intPreferencesKey("reminder_interval_hours")
        val START = intPreferencesKey("reminder_start_hour")
        val END = intPreferencesKey("reminder_end_hour")
        val FOCUS = booleanPreferencesKey("focus_mode_enabled")
        val ALLOWED = stringSetPreferencesKey("allowed_apps")
        val ALLOWED_INIT = booleanPreferencesKey("allowed_apps_initialized")
        val CAL_CONNECTED = booleanPreferencesKey("calendar_connected")
        val CAL_ID = stringPreferencesKey("calendar_id")
        val CAL_NAME = stringPreferencesKey("calendar_name")
        val SETTINGS_UPDATED = longPreferencesKey("settings_updated_at")
        val SETTINGS_DIRTY = booleanPreferencesKey("settings_dirty")
        val LAST_REMINDER = longPreferencesKey("last_reminder_at")
        val PLAN_SEED_VERSION = intPreferencesKey("plan_seed_version")
        val PLAN_SEEDED_COUNT = intPreferencesKey("plan_seeded_count")
        val REMOTE_SEEDED_UID = stringPreferencesKey("remote_seeded_uid")
        val LAST_SYNCED_UID = stringPreferencesKey("last_synced_uid")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val FCM_TOKEN = stringPreferencesKey("fcm_token")
        val CAL_LAST_SYNC_AT = longPreferencesKey("calendar_last_sync_at")
        val CAL_LAST_SYNC_MSG = stringPreferencesKey("calendar_last_sync_message")
        val CAL_NEEDS_REAUTH = booleanPreferencesKey("calendar_needs_reauth")
        val CAL_IGNORED = stringSetPreferencesKey("calendar_ignored_event_ids")
    }
}

/** Settings as stored in `users/{uid}/settings/preferences`. */
data class RemoteSettings(
    val remindersEnabled: Boolean,
    val reminderIntervalHours: Int,
    val reminderStartHour: Int,
    val reminderEndHour: Int,
    val focusModeEnabled: Boolean,
    val allowedApps: Set<String>,
    val calendarId: String?,
    val updatedAt: Long,
)

/** Clamps reminder values into the ranges accepted by the UI and the Firestore rules (start < end). */
fun AppSettings.sanitized(): AppSettings {
    val r: ReminderSettings = reminderSettings.sanitized
    val end = if (r.endHour <= r.startHour) (r.startHour + 1).coerceAtMost(24) else r.endHour
    return copy(
        reminderIntervalHours = r.intervalHours,
        reminderStartHour = r.startHour,
        reminderEndHour = end,
    )
}
