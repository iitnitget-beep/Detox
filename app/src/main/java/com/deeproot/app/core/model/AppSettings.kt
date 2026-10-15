package com.deeproot.app.core.model

import com.deeproot.app.core.reminder.ReminderSettings

/**
 * User preferences. Stored locally in DataStore (source of truth on the device) and mirrored to
 * `users/{uid}/settings/preferences` so the scheduled Cloud Function can respect the same reminder window.
 */
data class AppSettings(
    val onboardingComplete: Boolean = false,
    val remindersEnabled: Boolean = true,
    val reminderIntervalHours: Int = 2,
    val reminderStartHour: Int = 8,
    val reminderEndHour: Int = 22,
    val focusModeEnabled: Boolean = true,
    val allowedApps: Set<String> = emptySet(),
    val allowedAppsInitialized: Boolean = false,
    val calendarConnected: Boolean = false,
    val calendarId: String? = null,
    val calendarName: String? = null,
    val updatedAt: Long = 0L,
) {
    val reminderSettings: ReminderSettings
        get() = ReminderSettings(
            enabled = remindersEnabled,
            intervalHours = reminderIntervalHours,
            startHour = reminderStartHour,
            endHour = reminderEndHour,
        )
}
