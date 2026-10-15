package com.deeproot.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deeproot.app.data.repository.SettingsRepository
import com.deeproot.app.focus.StudySessionController
import com.deeproot.app.permissions.PermissionChecker
import com.deeproot.app.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val permissionChecker: PermissionChecker,
    private val controller: StudySessionController,
) : ViewModel() {

    private val _startDestination = MutableStateFlow<String?>(null)

    /** Null until DataStore is read (a few ms); decided once so later changes don't reset navigation. */
    val startDestination: StateFlow<String?> = _startDestination.asStateFlow()

    init {
        viewModelScope.launch {
            _startDestination.value = if (settings.current().onboardingComplete) Routes.TODAY else Routes.ONBOARDING
        }
    }

    /** Every time the app comes to the foreground: permissions may have been revoked, a session may need its service. */
    fun onResume() {
        permissionChecker.refresh()
        viewModelScope.launch { controller.ensureServiceRunning() }
    }
}
