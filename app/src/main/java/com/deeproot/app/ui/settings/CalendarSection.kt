package com.deeproot.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deeproot.app.ui.components.AppCard
import com.deeproot.app.ui.components.SettingRow
import java.text.DateFormat
import java.util.Date

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Google account + Google Calendar import. [onMessage] shows a snackbar. */
@Composable
fun CalendarSection(onMessage: (String) -> Unit, viewModel: CalendarViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onConsentResult(result.resultCode, result.data)
    }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is CalendarUiEvent.LaunchConsent ->
                    consentLauncher.launch(IntentSenderRequest.Builder(event.pendingIntent.intentSender).build())
                is CalendarUiEvent.Message -> onMessage(event.text)
            }
        }
    }

    state.calendars?.let { calendars ->
        CalendarPickerDialog(
            calendars = calendars,
            selectedId = state.settings.calendarId,
            onPick = viewModel::pickCalendar,
            onDismiss = viewModel::dismissPicker,
        )
    }

    val user = state.user
    val settings = state.settings
    AppCard {
        Column {
            SettingRow(
                title = if (user == null || user.isAnonymous) "Sign in with Google" else (user.email ?: "Google account"),
                subtitle = if (user == null || user.isAnonymous) {
                    "Keeps your progress if you reinstall or switch phones. Your current progress is kept."
                } else {
                    "Signed in — your progress is backed up to your account"
                },
                icon = Icons.Outlined.AccountCircle,
                trailing = {
                    if (user == null || user.isAnonymous) {
                        FilledTonalButton(
                            onClick = { activity?.let(viewModel::signIn) },
                            enabled = state.busy == null && activity != null,
                        ) { Text("Sign in") }
                    }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (!settings.calendarConnected) {
                SettingRow(
                    title = "Google Calendar",
                    subtitle = "Import lecture events for the next 60 days (read-only). Subjects are guessed from the title.",
                    icon = Icons.Outlined.CalendarMonth,
                )
                Row(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp)) {
                    Button(
                        onClick = { activity?.let(viewModel::connect) },
                        enabled = state.busy == null && activity != null,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Connect Google Calendar") }
                }
            } else {
                val flags = state.flags
                val last = flags.calendarLastSyncAt?.let {
                    "Last sync " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
                }
                SettingRow(
                    title = settings.calendarName ?: "Google Calendar",
                    subtitle = listOfNotNull(last, flags.calendarLastSyncMessage).joinToString("\n").ifEmpty {
                        "Syncs when you open the app and every 6 hours"
                    },
                    icon = Icons.Outlined.EventRepeat,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { activity?.let(viewModel::syncNow) },
                        enabled = state.busy == null && activity != null,
                    ) { Text(if (flags.calendarNeedsReauth) "Reconnect" else "Sync now") }
                    OutlinedButton(onClick = { activity?.let(viewModel::connect) }, enabled = state.busy == null) { Text("Change") }
                    TextButton(onClick = viewModel::disconnect, enabled = state.busy == null) { Text("Disconnect") }
                }
            }
            state.busy?.let { label ->
                Row(
                    Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun CalendarPickerDialog(
    calendars: List<com.deeproot.app.data.calendar.CalendarInfo>,
    selectedId: String?,
    onPick: (com.deeproot.app.data.calendar.CalendarInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a calendar") },
        text = {
            LazyColumn {
                items(calendars, key = { it.id }) { calendar ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = calendar.id == selectedId, role = Role.RadioButton, onClick = { onPick(calendar) })
                            .heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = calendar.id == selectedId, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(calendar.name, style = MaterialTheme.typography.bodyLarge)
                            if (calendar.primary) {
                                Text("Primary", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
