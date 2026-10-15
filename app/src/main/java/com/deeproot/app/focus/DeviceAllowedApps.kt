package com.deeproot.app.focus

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.telecom.TelecomManager

/**
 * Packages that must never be blocked on this particular device, resolved at runtime: the default dialer, every
 * launcher (Home/Recents), the Settings app and the keyboard.
 * Combined with [com.deeproot.app.core.focus.FocusPolicy.ALWAYS_ALLOWED_PREFIXES].
 */
object DeviceAllowedApps {

    fun resolve(context: Context): Set<String> {
        val pm = context.packageManager
        val result = mutableSetOf<String>()
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { result += it }
        result += packagesFor(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        result += packagesFor(pm, Intent(Intent.ACTION_DIAL))
        result += packagesFor(pm, Intent(Settings.ACTION_SETTINGS))
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
            ?.let { result += it }
        return result
    }

    @Suppress("DEPRECATION")
    private fun packagesFor(pm: PackageManager, intent: Intent): Set<String> =
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).mapNotNullTo(mutableSetOf()) {
            it.activityInfo?.packageName
        }
}
