package com.deeproot.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deeproot.app.data.apps.InstalledAppsRepository
import com.deeproot.app.data.repository.SettingsRepository
import com.deeproot.app.permissions.PermissionChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    val checker: PermissionChecker,
    private val settings: SettingsRepository,
    private val apps: InstalledAppsRepository,
) : ViewModel() {

    private val _finishing = MutableStateFlow(false)
    val finishing: StateFlow<Boolean> = _finishing.asStateFlow()

    /** Detected PW app(s) that will be allowed during Focus mode. */
    private val _defaultAllowedLabels = MutableStateFlow<List<String>>(emptyList())
    val defaultAllowedLabels: StateFlow<List<String>> = _defaultAllowedLabels.asStateFlow()

    init {
        viewModelScope.launch {
            val installed = apps.launchableApps()
            val defaults = apps.defaultAllowed(installed)
            _defaultAllowedLabels.value = installed.filter { it.packageName in defaults }.map { it.label }
        }
    }

    fun finish(onDone: () -> Unit) {
        if (_finishing.value) return
        _finishing.value = true
        viewModelScope.launch {
            val current = settings.current()
            if (!current.allowedAppsInitialized) {
                val installed = apps.launchableApps()
                val defaults = apps.defaultAllowed(installed)
                settings.update { it.copy(allowedApps = it.allowedApps + defaults, allowedAppsInitialized = true) }
            }
            settings.setOnboardingComplete(true)
            onDone()
        }
    }
}
