package com.deeproot.app.ui.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.RemoveModerator
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deeproot.app.core.model.ActiveSession
import com.deeproot.app.core.timer.TimeFormat
import com.deeproot.app.ui.components.EmptyView
import com.deeproot.app.ui.components.LoadingView
import com.deeproot.app.ui.components.SubjectChip
import com.deeproot.app.ui.theme.AppTheme
import com.deeproot.app.ui.theme.TimerTextStyle
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusScreen(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    viewModel: FocusViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                FocusEvent.Closed -> onBack()
            }
        }
    }

    if (state.confirmStop) {
        val studied = state.session?.timer?.studiedSeconds(System.currentTimeMillis()) ?: 0L
        AlertDialog(
            onDismissRequest = viewModel::dismissStop,
            title = { Text("Stop without completing?") },
            text = { Text("${TimeFormat.shortDuration(studied)} of study time will be saved. The lecture stays pending.") },
            confirmButton = { TextButton(onClick = viewModel::stopWithoutCompleting) { Text("Stop & save time") } },
            dismissButton = { TextButton(onClick = viewModel::dismissStop) { Text("Keep studying") } },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Study session") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back (the session keeps running)")
                    }
                },
            )
        },
    ) { inner ->
        val session = state.session
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            when {
                state.loading -> LoadingView("Loading session…")
                session == null -> EmptyView(
                    emoji = "✅",
                    title = "No session running",
                    message = "Start a lecture from Today.",
                    action = { Button(onClick = onBack) { Text("Back to Today") } },
                )
                else -> SessionContent(state, session, viewModel, onOpenPermissions)
            }
        }
    }
}

@Composable
private fun SessionContent(
    state: FocusUiState,
    session: ActiveSession,
    viewModel: FocusViewModel,
    onOpenPermissions: () -> Unit,
) {
    // Re-derive the display from timestamps every second (the stored state never "ticks").
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.timer.isRunning) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L - (now % 1_000L))
        }
    }
    val timer = session.timer
    val studied = timer.studiedMillis(now)
    val remaining = timer.remainingMillis(now)
    val overtime = remaining <= 0

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SubjectChip(session.subject)
        Spacer(Modifier.height(8.dp))
        Text(session.lectureTitle, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))

        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { timer.progress(now) },
                modifier = Modifier.size(280.dp),
                strokeWidth = 12.dp,
                color = if (overtime) AppTheme.extended.success else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    TimeFormat.hms(studied),
                    style = TimerTextStyle,
                    modifier = Modifier.semantics { contentDescription = "Studied ${TimeFormat.shortDuration(studied / 1000)}" },
                )
                Text(
                    if (timer.isPaused) "Paused" else "studied",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (timer.isPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = if (overtime) {
                "Planned ${TimeFormat.minutesLabel(session.plannedMinutes)} reached · +${TimeFormat.hms(-remaining)}"
            } else {
                "${TimeFormat.hms(remaining)} left of ${TimeFormat.minutesLabel(session.plannedMinutes)}"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (overtime) AppTheme.extended.success else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(12.dp))
        FocusStatusChip(state, onOpenPermissions)

        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = { if (timer.isRunning) viewModel.pause() else viewModel.resume() },
                enabled = !state.busy,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp),
            ) {
                Icon(if (timer.isRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (timer.isRunning) "Pause" else "Resume", style = MaterialTheme.typography.titleMedium)
            }
            Button(
                onClick = viewModel::finish,
                enabled = !state.busy,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (overtime) "Finish" else "Finish early", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = viewModel::requestStop, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Stop without completing")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Finish marks the lecture completed and saves your study time. You can leave this screen — the timer keeps running in the notification.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FocusStatusChip(state: FocusUiState, onOpenPermissions: () -> Unit) {
    val allowed = state.settings.allowedApps.size
    when {
        !state.settings.focusModeEnabled -> AssistChip(
            onClick = {},
            label = { Text("Focus mode off") },
            leadingIcon = { Icon(Icons.Outlined.RemoveModerator, contentDescription = null) },
        )
        !state.permissions.focusReady -> AssistChip(
            onClick = onOpenPermissions,
            label = { Text("Focus mode needs permissions — fix") },
            leadingIcon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        )
        state.session?.plannedEndNotified == true -> AssistChip(
            onClick = {},
            label = { Text("Focus mode ended (planned time reached)") },
            leadingIcon = { Icon(Icons.Outlined.RemoveModerator, contentDescription = null) },
        )
        else -> AssistChip(
            onClick = {},
            label = { Text("Focus mode on · $allowed allowed app${if (allowed == 1) "" else "s"}") },
            leadingIcon = { Icon(Icons.Outlined.Shield, contentDescription = null, tint = AppTheme.extended.success) },
        )
    }
}
