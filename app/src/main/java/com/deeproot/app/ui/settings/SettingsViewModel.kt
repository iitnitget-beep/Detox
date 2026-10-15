package com.deeproot.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deeproot.app.core.model.AppSettings
import com.deeproot.app.data.repository.SettingsRepository
import com.deeproot.app.data.seed.PlanSeeder
import com.deeproot.app.data.seed.SeedState
import com.deeproot.app.data.sync.SyncManager
import com.deeproot.app.data.sync.SyncStatus
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
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncManager: SyncManager,
    seeder: PlanSeeder,
) : ViewModel() {

    private val syncingNow = MutableStateFlow(false)
    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow: Flow<String> = messages.receiveAsFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        syncManager.status,
        seeder.state,
        syncingNow,
    ) { settings, sync, seed, busy -> SettingsUiState(settings, sync, seed, busy) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

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
