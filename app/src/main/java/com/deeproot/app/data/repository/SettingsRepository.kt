package com.deeproot.app.data.repository

import com.deeproot.app.core.model.AppSettings
import com.deeproot.app.core.time.TimeSource
import com.deeproot.app.data.prefs.LocalFlags
import com.deeproot.app.data.prefs.SettingsDataStore
import com.deeproot.app.data.sync.SyncTrigger
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** User preferences: DataStore first, mirrored to `users/{uid}/settings/preferences` by the sync layer. */
@Singleton
class SettingsRepository @Inject constructor(
    private val store: SettingsDataStore,
    private val time: TimeSource,
    private val syncTrigger: SyncTrigger,
) {
    val settings: Flow<AppSettings> = store.settings

    val flags: Flow<LocalFlags> = store.flags

    suspend fun current(): AppSettings = store.current()

    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val updated = store.update(time.nowMillis(), transform)
        syncTrigger.requestPush()
        return updated
    }

    suspend fun setOnboardingComplete(complete: Boolean) = store.setOnboardingComplete(complete)

    suspend fun setCalendar(connected: Boolean, calendarId: String?, calendarName: String?) {
        store.setCalendar(connected, calendarId, calendarName, time.nowMillis())
        syncTrigger.requestPush()
    }
}
