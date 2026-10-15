package com.deeproot.app.ui.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deeproot.app.core.model.ActiveSession
import com.deeproot.app.core.model.AppSettings
import com.deeproot.app.core.timer.TimeFormat
import com.deeproot.app.data.repository.SessionRepository
import com.deeproot.app.data.repository.SettingsRepository
import com.deeproot.app.focus.StudySessionController
import com.deeproot.app.permissions.PermissionChecker
import com.deeproot.app.permissions.PermissionState
import com.deeproot.app.ui.components.MessageCenter
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

data class FocusUiState(
    val loading: Boolean = true,
    val session: ActiveSession? = null,
    val settings: AppSettings = AppSettings(),
    val permissions: PermissionState = PermissionState(),
    val busy: Boolean = false,
    val confirmStop: Boolean = false,
) {
    val focusActive: Boolean get() = settings.focusModeEnabled && permissions.focusReady && session?.plannedEndNotified == false
}

sealed interface FocusEvent {
    /** The session ended; leave the screen. */
    data object Closed : FocusEvent
}

@HiltViewModel
class FocusViewModel @Inject constructor(
    sessions: SessionRepository,
    settingsRepository: SettingsRepository,
    permissionChecker: PermissionChecker,
    private val controller: StudySessionController,
    private val messages: MessageCenter,
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val confirmStop = MutableStateFlow(false)
    private val events = Channel<FocusEvent>(Channel.BUFFERED)
    val eventFlow: Flow<FocusEvent> = events.receiveAsFlow()

    val uiState: StateFlow<FocusUiState> = combine(
        sessions.active,
        settingsRepository.settings,
        permissionChecker.state,
        busy,
        confirmStop,
    ) { session, settings, permissions, isBusy, stop ->
        FocusUiState(
            loading = false,
            session = session,
            settings = settings,
            permissions = permissions,
            busy = isBusy,
            confirmStop = stop,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FocusUiState())

    fun pause() = launchAction { controller.pause() }

    fun resume() = launchAction { controller.resume() }

    fun finish() = launchAction {
        val result = controller.finish(markCompleted = true)
        if (result != null) {
            val studied = if (result.studiedSeconds >= 60) " · ${TimeFormat.shortDuration(result.studiedSeconds)} studied" else ""
            messages.post("Completed ${result.lectureName}$studied")
        }
        events.send(FocusEvent.Closed)
    }

    fun requestStop() {
        confirmStop.value = true
    }

    fun dismissStop() {
        confirmStop.value = false
    }

    /** Ends the session and keeps the studied time, without marking the lecture completed. */
    fun stopWithoutCompleting() = launchAction {
        confirmStop.value = false
        val result = controller.finish(markCompleted = false)
        if (result != null) {
            messages.post("Saved ${TimeFormat.shortDuration(result.studiedSeconds)} for ${result.lectureName}")
        }
        events.send(FocusEvent.Closed)
    }

    private fun launchAction(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                busy.value = false
            }
        }
    }
}
