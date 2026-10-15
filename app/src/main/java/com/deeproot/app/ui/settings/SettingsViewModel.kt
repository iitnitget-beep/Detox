package com.deeproot.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deeproot.app.core.model.AppSettings
import com.deeproot.app.data.repository.SettingsRepository
import com.deeproot.app.data.seed.PlanSeeder
import com.deeproot.app.data.seed.SeedState
import com.deeproot.app.data.sync.SyncManager
import com.deeproot.app.data.sync.SyncStatus
import com.deeproot.app.permissions.PermissionChecker
import com.deeproot.app.permissions.PermissionState
import com.deeproot.app.reminders.ReminderNotifier
import com.deeproot.app.reminders.ReminderScheduler
import java.time.LocalDateTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val sync: SyncStatus = SyncStatus(),
    val seed: SeedState = SeedState.Idle,
    val syncingNow: Boolean = false,
    val permissions: PermissionState = PermissionState(),
    val nextReminder: LocalDateTime? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncManager: SyncManager,
    seeder: PlanSeeder,
    permissionChecker: PermissionChecker,
    private val reminderScheduler: ReminderScheduler,
    private val reminderNotifier: ReminderNotifier,
) : ViewModel() {

    private val syncingNow = MutableStateFlow(false)
    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow: Flow<String> = messages.receiveAsFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        syncManager.status,
        seeder.state,
        syncingNow,
        permissionChecker.state,
    ) { settings, sync, seed, busy, permissions ->
        SettingsUiState(
            settings = settings,
            sync = sync,
            seed = seed,
            syncingNow = busy,
            permissions = permissions,
            nextReminder = if (settings.onboardingComplete && permissions.notifications) reminderScheduler.nextSlotTime(settings) else null,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setFocusMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.update { it.copy(focusModeEnabled = enabled) } }
    }

    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.update { it.copy(remindersEnabled = enabled) } }
    }

    fun setInterval(hours: Int) {
        viewModelScope.launch { settingsRepository.update { it.copy(reminderIntervalHours = hours.coerceIn(1, 12)) } }
    }

    /** Keeps start < end: moving the start past the end pushes the end along (and vice versa). */
    fun setStartHour(hour: Int) {
        viewModelScope.launch {
            settingsRepository.update {
                val start = hour.coerceIn(0, 23)
                it.copy(reminderStartHour = start, reminderEndHour = maxOf(it.reminderEndHour, start + 1))
            }
        }
    }

    fun setEndHour(hour: Int) {
        viewModelScope.launch {
            settingsRepository.update {
                val end = hour.coerceIn(1, 24)
                it.copy(reminderEndHour = end, reminderStartHour = minOf(it.reminderStartHour, end - 1))
            }
        }
    }

    fun sendTestReminder() {
        viewModelScope.launch {
            val shown = reminderNotifier.showTest()
            messages.send(if (shown) "Test reminder sent" else "Allow notifications first (Settings → Permissions)")
        }
    }

    fun syncNow() {
        if (syncingNow.value) return
        syncingNow.value = true
        viewModelScope.launch {
            val result = syncManager.syncNow()
            syncingNow.value = false
            messages.send(result.fold(onSuccess = { "Synced ✓" }, onFailure = { it.message ?: "Sync failed" }))
        }
    }
}
