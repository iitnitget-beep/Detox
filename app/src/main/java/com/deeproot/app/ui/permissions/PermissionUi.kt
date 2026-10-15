package com.deeproot.app.ui.permissions

import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deeproot.app.permissions.AppPermission
import com.deeproot.app.permissions.PermissionChecker
import com.deeproot.app.ui.components.AppCard
import com.deeproot.app.ui.theme.AppTheme

/** Plain-language explainer shown before every permission request. */
data class PermissionCopy(
    val emoji: String,
    val title: String,
    val why: String,
    val how: String,
)

fun copyFor(permission: AppPermission): PermissionCopy = when (permission) {
    AppPermission.NOTIFICATIONS -> PermissionCopy(
        emoji = "🔔",
        title = "Notifications",
        why = "Reminds you every few hours while lectures are pending (e.g. \"3 lectures pending. Next: Physics – Rotation 2\") " +
            "and shows the running study timer.",
        how = "Tap Allow, then choose Allow in the Android dialog.",
    )
    AppPermission.USAGE_ACCESS -> PermissionCopy(
        emoji = "📊",
        title = "Usage access",
        why = "Lets Focus mode see which app is open during a study session, so it can cover distracting apps. " +
            "It is only checked while a session runs, and nothing leaves your phone.",
        how = "Tap Allow, find Lecture Tracker in the list and turn on \"Permit usage access\", then come back.",
    )
    AppPermission.OVERLAY -> PermissionCopy(
        emoji = "🛡️",
        title = "Display over other apps",
        why = "Lets Focus mode show the \"Focus mode: finish your lecture\" screen on top of distracting apps. " +
            "The phone, emergency calls, Settings and your allowed apps are never covered.",
        how = "Tap Allow, turn on \"Allow display over other apps\" for Lecture Tracker, then come back.",
    )
    AppPermission.BATTERY -> PermissionCopy(
        emoji = "🔋",
        title = "Unrestricted battery use",
        why = "Without it Android may delay reminders and pause the timer service when the screen is off (Doze / battery saver).",
        how = "Tap Allow, then confirm \"Allow\" in the Android dialog.",
    )
}

/**
 * Returns a function that requests a permission the right way: runtime dialog for notifications (falling back to the
 * app's notification settings after a permanent denial), system settings screens for the special permissions.
 * [onReturn] runs when the user comes back, after the state was re-checked.
 */
@Composable
fun rememberPermissionRequester(checker: PermissionChecker, onReturn: () -> Unit = {}): (AppPermission) -> Unit {
    var notificationAsked by rememberSaveable { mutableStateOf(false) }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checker.refresh()
        onReturn()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationAsked = true
        checker.refresh()
        onReturn()
    }
    return { permission: AppPermission ->
        if (permission == AppPermission.NOTIFICATIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationAsked) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            try {
                settingsLauncher.launch(checker.settingsIntent(permission))
            } catch (e: ActivityNotFoundException) {
                try {
                    settingsLauncher.launch(checker.fallbackIntent(permission))
                } catch (inner: ActivityNotFoundException) {
                    onReturn()
                }
            }
        }
    }
}

/** Card with the explainer, status and the Allow button (used in the Permissions screen). */
@Composable
fun PermissionCard(
    permission: AppPermission,
    granted: Boolean,
    onAllow: () -> Unit,
    modifier: Modifier = Modifier,
    neededFor: String? = null,
) {
    val copy = copyFor(permission)
    AppCard(modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(copy.emoji, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(copy.title, style = MaterialTheme.typography.titleMedium)
                    if (neededFor != null) {
                        Text(neededFor, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (granted) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "Allowed", tint = AppTheme.extended.success)
                }
            }
            Text(copy.why, style = MaterialTheme.typography.bodyMedium)
            if (!granted) {
                Text(copy.how, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Allow") }
            } else {
                TextButton(onClick = onAllow) { Text("Open system settings") }
            }
        }
    }
}
