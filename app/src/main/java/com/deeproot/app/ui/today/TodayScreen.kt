package com.deeproot.app.ui.today

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deeproot.app.core.model.Lecture
import com.deeproot.app.core.time.Dates
import com.deeproot.app.ui.components.AppCard
import com.deeproot.app.ui.components.Banner
import com.deeproot.app.ui.components.EmptyView
import com.deeproot.app.ui.components.ErrorView
import com.deeproot.app.ui.components.LoadingView
import com.deeproot.app.ui.components.OfflineBanner
import com.deeproot.app.ui.theme.AppTheme

/**
 * Today screen. [onStartLecture] is null until the focus timer exists (Stage 2); [topContent] lets the caller add
 * banners such as "session running" or "permission missing".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onStartLecture: ((Lecture) -> Unit)? = null,
    topContent: @Composable () -> Unit = {},
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is TodayEvent.ShowUndo -> {
                    val result = snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = "Undo",
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undo(event.undo)
                }
                is TodayEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }

    if (state.showDeleteConfirm) {
        val n = state.selection.size
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text(if (n == 1) "Delete 1 lecture?" else "Delete $n lectures?") },
            text = { Text("They will be removed from all your devices. You can undo right after deleting.") },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissDelete) { Text("Cancel") } },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (state.selectionMode) {
                SelectionTopBar(
                    count = state.selection.size,
                    onClose = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    onMarkCompleted = viewModel::markSelectedCompleted,
                    onDelete = viewModel::requestDeleteSelected,
                )
            } else {
                TodayTopBar(state)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        when {
            state.loading -> LoadingView("Loading your lecture plan…", Modifier.padding(inner))
            state.error != null -> Column(Modifier.padding(inner).padding(16.dp)) {
                ErrorView(
                    title = "Couldn't load the lecture plan",
                    message = state.error ?: "",
                    onRetry = viewModel::retrySeed,
                )
            }
            else -> TodayList(
                state = state,
                contentPadding = inner,
                topContent = topContent,
                onPrev = viewModel::previousDay,
                onNext = viewModel::nextDay,
                onToday = viewModel::goToToday,
                onRowClick = { lecture ->
                    if (state.selectionMode) viewModel.toggleSelection(lecture) else viewModel.toggleCompleted(lecture)
                },
                onRowLongClick = { lecture ->
                    if (state.selectionMode) viewModel.toggleSelection(lecture) else viewModel.onLongPress(lecture)
                },
                onStart = onStartLecture,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodayTopBar(state: TodayUiState) {
    val subtitle = when {
        state.isBeforePlan -> "Starts ${state.plan?.firstDate?.let { Dates.shortLabel(it) } ?: ""}"
        state.todayPending + state.backlogCount > 0 -> "${state.todayPending} pending today · ${state.backlogCount} backlog"
        else -> "All caught up 🎉"
    }
    TopAppBar(
        title = {
            Column {
                Text("📚 Lecture Tracker", style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onMarkCompleted: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Cancel selection") }
        },
        title = { Text("$count selected") },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Filled.SelectAll, contentDescription = "Select all") }
            IconButton(onClick = onMarkCompleted) { Icon(Icons.Filled.DoneAll, contentDescription = "Mark all completed") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@Composable
private fun TodayList(
    state: TodayUiState,
    contentPadding: PaddingValues,
    topContent: @Composable () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onRowClick: (Lecture) -> Unit,
    onRowLongClick: (Lecture) -> Unit,
    onStart: ((Lecture) -> Unit)?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "banners") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.sync.online) OfflineBanner(state.sync.pendingChanges)
                if (state.sync.online && state.sync.lastError != null) {
                    Banner(icon = Icons.Outlined.SyncProblem, text = "Sync issue: ${state.sync.lastError}")
                }
                topContent()
            }
        }
        item(key = "progress") { ProgressCard(state) }
        item(key = "nav") { DateNavigator(state, onPrev, onNext, onToday) }
        item(key = "day") {
            AppCard {
                if (state.lectures.isEmpty()) {
                    DayEmptyState(state)
                } else {
                    Column {
                        state.lectures.forEachIndexed { index, lecture ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            LectureRow(
                                lecture = lecture,
                                selectionMode = state.selectionMode,
                                selected = lecture.id in state.selection,
                                onClick = { onRowClick(lecture) },
                                onLongClick = { onRowLongClick(lecture) },
                                onStart = onStart?.let { start -> { start(lecture) } },
                            )
                        }
                    }
                }
            }
        }
        if (state.backlogGroups.isNotEmpty()) {
            backlogSection(state, onRowClick, onRowLongClick, onStart)
        }
    }
}

private fun LazyListScope.backlogSection(
    state: TodayUiState,
    onRowClick: (Lecture) -> Unit,
    onRowLongClick: (Lecture) -> Unit,
    onStart: ((Lecture) -> Unit)?,
) {
    item(key = "backlog-header") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 4.dp)
                .semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = AppTheme.extended.backlog)
            Text(
                "  Backlog (${state.backlogCount})",
                style = MaterialTheme.typography.titleMedium,
                color = AppTheme.extended.backlog,
                fontWeight = FontWeight.Bold,
            )
        }
    }
    items(state.backlogGroups, key = { "backlog-${it.date}" }) { group ->
        AppCard {
            Column {
                Text(
                    Dates.shortLabel(group.date),
                    style = MaterialTheme.typography.labelLarge,
                    color = AppTheme.extended.backlog,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
                )
                group.lectures.forEachIndexed { index, lecture ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LectureRow(
                        lecture = lecture,
                        selectionMode = state.selectionMode,
                        selected = lecture.id in state.selection,
                        onClick = { onRowClick(lecture) },
                        onLongClick = { onRowLongClick(lecture) },
                        onStart = onStart?.let { start -> { start(lecture) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(state: TodayUiState) {
    AppCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${state.progress.completed}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppTheme.extended.success,
                )
                Text(
                    " of ${state.progress.total} lectures done",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { state.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50)),
                color = AppTheme.extended.success,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun DateNavigator(state: TodayUiState, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    val summary = when {
        state.isRestDay -> "NO LECTURE · rest day"
        state.lectures.isEmpty() -> "No lectures"
        state.dayPending == 0 -> "All done ✓"
        else -> "${state.dayPending} pending of ${state.lectures.size}"
    }
    AppCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        Dates.shortLabel(state.selectedDate) + if (state.isToday) " · Today" else "",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.dayPending == 0 && state.lectures.isNotEmpty()) {
                            AppTheme.extended.success
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
                }
            }
            if (!state.isToday) {
                TextButton(onClick = onToday, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Jump to today")
                }
            }
        }
    }
}

@Composable
private fun DayEmptyState(state: TodayUiState) {
    when {
        state.isRestDay -> EmptyView(
            emoji = "🌴",
            title = "NO LECTURE",
            message = if (state.backlogCount > 0) "Rest day — a good time to clear some backlog." else "Rest day. Enjoy it!",
        )
        state.plan?.firstDate?.let { state.selectedDate.isBefore(it) } == true -> EmptyView(
            emoji = "🗓️",
            title = "No lectures yet",
            message = "The plan starts on ${state.plan?.firstDate?.let { Dates.longLabel(it) }}.",
        )
        state.plan?.lastDate?.let { state.selectedDate.isAfter(it) } == true -> EmptyView(
            emoji = "🏁",
            title = "Past the end of the plan",
            message = "The plan ended on ${state.plan?.lastDate?.let { Dates.longLabel(it) }}.",
        )
        else -> EmptyView(emoji = "📭", title = "No lectures on this day", message = null)
    }
}
