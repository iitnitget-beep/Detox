package com.deeproot.app.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deeproot.app.data.apps.InstalledApp
import com.deeproot.app.ui.components.EmptyView
import com.deeproot.app.ui.components.LoadingView

/** Apps that stay usable during Focus mode (the PW app is pre-selected). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllowedAppsScreen(onBack: () -> Unit, viewModel: AllowedAppsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Allowed apps (${state.selected.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = inner.calculateTopPadding())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding(),
        ) {
            Text(
                "These apps stay usable during a study session. The phone, emergency calls, Settings and the home screen are always allowed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when {
                state.loading -> LoadingView("Loading installed apps…")
                state.filtered.isEmpty() -> EmptyView(emoji = "🔍", title = "No apps found", message = null)
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    // Selected apps first so the current choice is visible at a glance.
                    val sorted = state.filtered.sortedByDescending { it.packageName in state.selected }
                    items(sorted, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            checked = app.packageName in state.selected,
                            onToggle = { viewModel.toggle(app.packageName) },
                            loadIcon = { size -> viewModel.icon(app.packageName, size) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit, loadIcon: suspend (Int) -> Bitmap?) {
    val sizePx = with(LocalDensity.current) { 40.dp.roundToPx() }
    val icon by produceState<Bitmap?>(initialValue = null, app.packageName) { value = loadIcon(sizePx) }
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            icon?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp)) }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}
