package com.deeproot.app.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class AppPermission { NOTIFICATIONS, USAGE_ACCESS, OVERLAY, BATTERY }

data class PermissionState(
    val notifications: Boolean = false,
    val usageAccess: Boolean = false,
    val overlay: Boolean = false,
    val batteryUnrestricted: Boolean = false,
) {
    /** Focus mode needs both: who is in the foreground (usage access) and a way to cover it (overlay). */
    val focusReady: Boolean get() = usageAccess && overlay

    fun isGranted(permission: AppPermission): Boolean = when (permission) {
        AppPermission.NOTIFICATIONS -> notifications
        AppPermission.USAGE_ACCESS -> usageAccess
        AppPermission.OVERLAY -> overlay
        AppPermission.BATTERY -> batteryUnrestricted
    }

    val missing: List<AppPermission> get() = AppPermission.entries.filterNot { isGranted(it) }
}

/** Checks the four special permissions and builds the intents that open the matching system screens. */
@Singleton
class PermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _state = MutableStateFlow(read())

    /** Updated by [refresh]; MainActivity calls it on every resume (permissions can be revoked any time). */
    val state: StateFlow<PermissionState> = _state.asStateFlow()

    fun refresh(): PermissionState = read().also { _state.value = it }

    fun current(): PermissionState = _state.value

    private fun read(): PermissionState = PermissionState(
        notifications = hasNotificationPermission(),
        usageAccess = hasUsageAccess(),
        overlay = Settings.canDrawOverlays(context),
        batteryUnrestricted = isIgnoringBatteryOptimizations(),
    )

    fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    @Suppress("DEPRECATION")
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * System screen for a special permission (notifications use the runtime dialog first, see the UI).
     * No FLAG_ACTIVITY_NEW_TASK: these are launched from an Activity and the result callback is used to re-check.
     */
    @SuppressLint("BatteryLife")
    fun settingsIntent(permission: AppPermission): Intent {
        val pkgUri = Uri.parse("package:${context.packageName}")
        val intent = when (permission) {
            AppPermission.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                // Android 10+ can open this app's page directly on many devices; falls back to the list otherwise.
                data = pkgUri
            }
            AppPermission.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
            AppPermission.BATTERY -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
            AppPermission.NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        return intent
    }

    /** Generic fallbacks used when a device does not support the specific screen. */
    fun fallbackIntent(permission: AppPermission): Intent {
        val intent = when (permission) {
            AppPermission.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            AppPermission.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        return intent
    }
}
