package com.deeproot.app.data.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import javax.inject.Inject
import javax.inject.Singleton

data class InstalledApp(
    val packageName: String,
    val label: String,
)

/**
 * Launchable apps on this device (visible through the `<queries>` LAUNCHER intent in the manifest, no
 * QUERY_ALL_PACKAGES needed).
 */
@Singleton
class InstalledAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val iconCache = LruCache<String, Bitmap>(120)

    @Suppress("DEPRECATION")
    suspend fun launchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val collator = Collator.getInstance()
        pm.queryIntentActivities(intent, 0)
            .asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                InstalledApp(pkg, info.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg })
            }
            .distinctBy { it.packageName }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
            .toList()
    }

    suspend fun icon(packageName: String, sizePx: Int): Bitmap? = withContext(Dispatchers.IO) {
        iconCache.get(packageName) ?: try {
            context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).also {
                iconCache.put(packageName, it)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** Physics Wallah ("PW") apps that are pre-selected the first time the allowed-apps list is shown. */
    fun defaultAllowed(installed: List<InstalledApp>): Set<String> =
        installed.filter { app ->
            app.packageName in PW_PACKAGES ||
                app.packageName.startsWith("xyz.penpencil.") ||
                app.label.equals("PW", ignoreCase = true) ||
                app.label.contains("Physics Wallah", ignoreCase = true) ||
                app.label.contains("PhysicsWallah", ignoreCase = true)
        }.mapTo(mutableSetOf()) { it.packageName }

    companion object {
        /** Known package of the Physics Wallah app on Google Play. */
        val PW_PACKAGES = setOf("xyz.penpencil.physicswala")
    }
}
