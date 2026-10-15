package com.deeproot.app.core.focus

/**
 * Pure decision logic for Focus mode: which foreground app must be covered by the block screen.
 *
 * Safety first: the phone dialer, in-call UI, emergency apps, system settings, the permission dialogs,
 * the launcher and the system UI are **never** blocked.
 */
object FocusPolicy {

    /** Package prefixes that are always allowed (dialer, emergency, settings, system UI, installers). */
    val ALWAYS_ALLOWED_PREFIXES: List<String> = listOf(
        // System UI, launcher-ish system surfaces
        "com.android.systemui",
        "android",
        // Phone, dialer, in-call and emergency
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.android.incallui",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.samsung.android.emergency",
        "com.android.contacts",
        "com.google.android.contacts",
        // Settings and permission screens
        "com.android.settings",
        "com.google.android.settings",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        // Clock / alarms so a ringing alarm can always be dismissed
        "com.google.android.deskclock",
        "com.android.deskclock",
        "com.sec.android.app.clockpackage",
    )

    /**
     * @param foregroundPackage package currently in the foreground (from UsageStatsManager), if known.
     * @param ownPackage this app's package name.
     * @param userAllowed apps the student allowed in Settings (e.g. the PW app).
     * @param deviceAllowed packages resolved at runtime: default dialer, launcher(s), settings app.
     */
    fun shouldBlock(
        foregroundPackage: String?,
        ownPackage: String,
        userAllowed: Set<String>,
        deviceAllowed: Set<String>,
    ): Boolean {
        val pkg = foregroundPackage?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        if (pkg == ownPackage) return false
        if (pkg in userAllowed || pkg in deviceAllowed) return false
        if (ALWAYS_ALLOWED_PREFIXES.any { prefix -> pkg == prefix || pkg.startsWith("$prefix.") }) return false
        return true
    }
}
