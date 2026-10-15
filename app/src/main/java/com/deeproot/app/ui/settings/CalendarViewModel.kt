package com.deeproot.app.ui.settings

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deeproot.app.calendar.CalendarAuthorization
import com.deeproot.app.calendar.GoogleAccountManager
import com.deeproot.app.core.model.AppSettings
import com.deeproot.app.data.auth.AuthRepository
import com.deeproot.app.data.auth.AuthUser
import com.deeproot.app.data.auth.LinkResult
import com.deeproot.app.data.calendar.CalendarInfo
import com.deeproot.app.data.calendar.CalendarRepository
import com.deeproot.app.data.calendar.CalendarSyncOutcome
import com.deeproot.app.data.prefs.LocalFlags
import com.deeproot.app.data.repository.SettingsRepository
import com.deeproot.app.data.sync.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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

data class CalendarUiState(
    val user: AuthUser? = null,
    val settings: AppSettings = AppSettings(),
    val flags: LocalFlags = LocalFlags(),
    val busy: String? = null,
    /** Non-null while the "Pick a calendar" dialog is open. */
    val calendars: List<CalendarInfo>? = null,
)

sealed interface CalendarUiEvent {
    data class LaunchConsent(val pendingIntent: PendingIntent) : CalendarUiEvent
    data class Message(val text: String) : CalendarUiEvent
}

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val google: GoogleAccountManager,
    private val repository: CalendarRepository,
    private val syncManager: SyncManager,
    auth: AuthRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val busy = MutableStateFlow<String?>(null)
    private val calendars = MutableStateFlow<List<CalendarInfo>?>(null)
    private val events = Channel<CalendarUiEvent>(Channel.BUFFERED)
    val eventFlow: Flow<CalendarUiEvent> = events.receiveAsFlow()

    /** What to do with the token once the consent screen returns. */
    private var afterConsent: Action = Action.LIST_CALENDARS

    private enum class Action { LIST_CALENDARS, SYNC }

    val uiState: StateFlow<CalendarUiState> = combine(
        auth.user,
        settingsRepository.settings,
        settingsRepository.flags,
        busy,
        calendars,
    ) { user, settings, flags, working, list -> CalendarUiState(user, settings, flags, working, list) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    /** Links the anonymous account to Google (keeps all data). */
    fun signIn(activity: Activity) = launch("Signing in…") {
        try {
            when (val result = google.signInWithGoogle(activity)) {
                is LinkResult.Linked -> message("Signed in as ${result.user.email ?: "Google account"} — your progress is kept")
                is LinkResult.SwitchedAccount -> {
                    // That Google account already had data: switch to it and merge this phone's progress into it.
                    syncManager.onAccountSwitched()
                    message("Switched to ${result.user.email ?: "your Google account"}; merging this phone's progress")
                }
            }
        } catch (e: GetCredentialCancellationException) {
            message("Sign-in cancelled")
        } catch (e: NoCredentialException) {
            message("No Google account on this phone. Add one in Android Settings → Accounts.")
        }
    }

    /** Connect: ask for calendar.readonly, then show the calendar picker. */
    fun connect(activity: Activity) = launch("Connecting…") {
        when (val authorization = google.authorizeCalendar(activity)) {
            is CalendarAuthorization.Granted -> showCalendars(authorization.accessToken)
            is CalendarAuthorization.NeedsConsent -> {
                afterConsent = Action.LIST_CALENDARS
                events.send(CalendarUiEvent.LaunchConsent(authorization.pendingIntent))
            }
            is CalendarAuthorization.Failed -> message(authorization.message)
        }
    }

    fun onConsentResult(resultCode: Int, data: Intent?) = launch("Connecting…") {
        val token = if (resultCode == Activity.RESULT_OK) google.tokenFromConsentResult(data) else null
        if (token == null) {
            message("Calendar access was not granted")
            return@launch
        }
        when (afterConsent) {
            Action.LIST_CALENDARS -> showCalendars(token)
            Action.SYNC -> handleOutcome(repository.syncWithFreshToken(token))
        }
    }

    fun pickCalendar(calendar: CalendarInfo) {
        calendars.value = null
        launch("Importing ${calendar.name}…") {
            repository.selectCalendar(calendar)
            handleOutcome(repository.syncNow(activity = null, source = "connect"))
        }
    }

    fun dismissPicker() {
        calendars.value = null
    }

    fun syncNow(activity: Activity) = launch("Syncing…") {
        handleOutcome(repository.syncNow(activity, source = "manual"))
    }

    fun disconnect() = launch("Disconnecting…") {
        repository.disconnect()
        message("Calendar disconnected. Imported lectures were kept.")
    }

    private suspend fun showCalendars(token: String) {
        val list = repository.listCalendars(token)
        if (list.isEmpty()) message("No calendars found in this Google account") else calendars.value = list
    }

    private suspend fun handleOutcome(outcome: CalendarSyncOutcome) {
        when (outcome) {
            is CalendarSyncOutcome.Success -> message("Calendar synced: ${outcome.summary}")
            is CalendarSyncOutcome.NeedsConsent -> {
                afterConsent = Action.SYNC
                events.send(CalendarUiEvent.LaunchConsent(outcome.pendingIntent))
            }
            CalendarSyncOutcome.NotConnected -> message("Connect a calendar first")
            CalendarSyncOutcome.Offline -> message("You're offline — calendar sync will run later")
            is CalendarSyncOutcome.Failed -> message(outcome.message)
        }
    }

    private suspend fun message(text: String) {
        events.send(CalendarUiEvent.Message(text))
    }

    private fun launch(label: String, block: suspend () -> Unit) {
        if (busy.value != null) return
        busy.value = label
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message(e.message ?: "Something went wrong")
            } finally {
                busy.value = null
            }
        }
    }
}
