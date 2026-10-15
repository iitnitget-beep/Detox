package com.deeproot.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deeproot.app.BuildConfig
import com.deeproot.app.core.time.Dates
import com.deeproot.app.data.seed.SeedState
import com.deeproot.app.data.sync.SyncPhase
import com.deeproot.app.ui.components.AppCard
import com.deeproot.app.ui.components.SectionTitle
import com.deeproot.app.ui.components.SettingRow
import java.text.DateFormat
import java.util.Date

/**
 * Settings. [extraSections] lets later stages add Reminders / Focus mode / Calendar sections above Account & sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    extraSections: @Composable () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.messageFlow.collect { snackbarHostState.showSnackbar(it) } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Settings") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = inner.calculateTopPadding() + 4.dp,
                bottom = inner.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { extraSections() }
            item { SectionTitle("Account & sync") }
            item { AccountCard(state, onSyncNow = viewModel::syncNow) }
            item { SectionTitle("Lecture plan") }
            item { PlanCard(state) }
            item {
                Text(
                    "Lecture Tracker ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun AccountCard(state: SettingsUiState, onSyncNow: () -> Unit) {
    val sync = state.sync
    AppCard {
        Column {
            val accountText = when {
                !sync.signedIn -> "Not signed in yet (needs internet once)"
                sync.isAnonymous -> "Guest account (anonymous). Link Google in the Calendar section to keep your data if you reinstall."
                else -> listOfNotNull(sync.displayName, sync.email).joinToString(" · ").ifBlank { "Google account" }
            }
            SettingRow(title = "Account", subtitle = accountText, icon = Icons.Outlined.AccountCircle)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val (statusIcon, statusText) = when {
                !sync.online -> Icons.Outlined.CloudOff to "Offline — changes are saved on this phone"
                sync.phase == SyncPhase.SYNCING || state.syncingNow -> Icons.Outlined.Sync to "Syncing…"
                sync.pendingChanges > 0 -> Icons.Outlined.CloudUpload to "${sync.pendingChanges} change(s) waiting to upload"
                else -> Icons.Outlined.CloudDone to "Everything is synced"
            }
            val lastSync = sync.lastSyncAt?.let {
                "Last full sync: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
            }
            SettingRow(
                title = statusText,
                subtitle = lastSync,
                icon = statusIcon,
                trailing = {
                    if (state.syncingNow) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        FilledTonalButton(onClick = onSyncNow, enabled = sync.online) { Text("Sync now") }
                    }
                },
            )
            if (sync.lastError != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(title = "Last sync problem", subtitle = sync.lastError, icon = Icons.Outlined.ErrorOutline)
            }
        }
    }
}

@Composable
private fun PlanCard(state: SettingsUiState) {
    AppCard {
        Column {
            when (val seed = state.seed) {
                is SeedState.Done -> {
                    val info = seed.info
                    val range = if (info.firstDate != null && info.lastDate != null) {
                        "${Dates.shortLabel(info.firstDate)} – ${Dates.shortLabel(info.lastDate)} ${info.lastDate.year}"
                    } else {
                        ""
                    }
                    SettingRow(
                        title = "${info.lectureCount} lectures · ${if (info.verified) "verified ✓" else "NOT verified"}",
                        subtitle = "$range · ${info.noLectureDates.size} NO LECTURE days. " +
                            "Parsed ${info.lectureCount} of ${info.lectureLineCount} lecture lines in lecture_plan.md.",
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val serverText = when {
                        state.sync.remoteSeeded -> {
                            val r = state.sync.lastSeedResult
                            if (r != null) {
                                "Cloud copy verified: ${r.totalPlanDocs}/${r.expected} plan lectures (created ${r.created} now)."
                            } else {
                                "Cloud copy created and verified."
                            }
                        }
                        state.sync.signedIn -> "Cloud copy will be created on the next sync."
                        else -> "Cloud copy is created after the first sign-in."
                    }
                    SettingRow(title = "Firestore", subtitle = serverText, icon = Icons.Outlined.Info)
                }
                is SeedState.Failed -> SettingRow(title = "Plan could not be loaded", subtitle = seed.message, icon = Icons.Outlined.ErrorOutline)
                else -> SettingRow(title = "Loading plan…", icon = Icons.AutoMirrored.Outlined.MenuBook)
            }
        }
    }
}
