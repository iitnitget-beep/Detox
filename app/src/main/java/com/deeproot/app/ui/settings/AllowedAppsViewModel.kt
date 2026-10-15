package com.deeproot.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deeproot.app.data.apps.InstalledApp
import com.deeproot.app.data.apps.InstalledAppsRepository
import com.deeproot.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AllowedAppsUiState(
    val loading: Boolean = true,
    val apps: List<InstalledApp> = emptyList(),
    val selected: Set<String> = emptySet(),
    val query: String = "",
) {
    val filtered: List<InstalledApp>
        get() = if (query.isBlank()) {
            apps
        } else {
            apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
        }
}

@HiltViewModel
class AllowedAppsViewModel @Inject constructor(
    private val repository: InstalledAppsRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val apps = MutableStateFlow<List<InstalledApp>?>(null)
    private val query = MutableStateFlow("")

    val uiState: StateFlow<AllowedAppsUiState> = combine(apps, settings.settings, query) { list, s, q ->
        AllowedAppsUiState(loading = list == null, apps = list.orEmpty(), selected = s.allowedApps, query = q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AllowedAppsUiState())

    init {
        viewModelScope.launch {
            val installed = repository.launchableApps()
            apps.value = installed
            // First visit: pre-select the PW app.
            if (!settings.current().allowedAppsInitialized) {
                val defaults = repository.defaultAllowed(installed)
                settings.update { it.copy(allowedApps = it.allowedApps + defaults, allowedAppsInitialized = true) }
            }
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun toggle(packageName: String) {
        viewModelScope.launch {
            settings.update {
                val next = if (packageName in it.allowedApps) it.allowedApps - packageName else it.allowedApps + packageName
                it.copy(allowedApps = next, allowedAppsInitialized = true)
            }
        }
    }

    suspend fun icon(packageName: String, sizePx: Int) = repository.icon(packageName, sizePx)
}
