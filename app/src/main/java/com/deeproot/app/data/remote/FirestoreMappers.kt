package com.deeproot.app.data.remote

import com.deeproot.app.core.model.AppSettings
import com.deeproot.app.core.model.Lecture
import com.deeproot.app.core.model.LectureSource
import com.deeproot.app.core.model.StudySession
import com.deeproot.app.core.model.Subject
import com.deeproot.app.core.time.Dates
import com.deeproot.app.data.prefs.RemoteSettings
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import java.util.Date

/** Firestore document paths. Every user's data lives under `users/{uid}`. */
object FirestorePaths {
    const val USERS = "users"
    const val LECTURES = "lectures"
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val SETTINGS_DOC = "preferences"
    const val STATS = "stats"
    const val STATS_SUMMARY_DOC = "summary"
}

/** Field names, kept in one place so they match `firestore.rules` and the Cloud Functions. */
object Fields {
    const val DATE = "date"
    const val ORDER = "order"
    const val SUBJECT = "subject"
    const val TITLE = "title"
    const val COMPLETED = "completed"
    const val COMPLETED_AT = "completedAt"
    const val PLANNED_MINUTES = "plannedMinutes"
    const val STUDIED_SECONDS = "studiedSeconds"
    const val SOURCE = "source"
    const val CALENDAR_EVENT_ID = "calendarEventId"
    const val UPDATED_AT = "updatedAt"

    const val LECTURE_ID = "lectureId"
    const val STARTED_AT = "startedAt"
    const val ENDED_AT = "endedAt"

    const val REMINDER_INTERVAL_HOURS = "reminderIntervalHours"
    const val REMINDER_START_HOUR = "reminderStartHour"
    const val REMINDER_END_HOUR = "reminderEndHour"
    const val REMINDERS_ENABLED = "remindersEnabled"
    const val ALLOWED_APPS = "allowedApps"
    const val FOCUS_MODE_ENABLED = "focusModeEnabled"
    const val CALENDAR_ID = "calendarId"
    const val TIME_ZONE = "timeZone"

    const val PLATFORM = "platform"
    const val APP_VERSION = "appVersion"
    const val FCM_TOKEN = "fcmToken"
    const val PLAN_SEEDED = "planSeeded"
    const val PLAN_SEEDED_COUNT = "planSeededCount"
    const val LAST_LOCAL_REMINDER_AT = "lastLocalReminderAt"
    const val CREATED_AT = "createdAt"
}

fun Long.toTimestamp(): Timestamp = Timestamp(Date(this))

private fun DocumentSnapshot.millis(field: String): Long? = getTimestamp(field)?.toDate()?.time

object LectureMapper {

    fun toMap(l: Lecture): Map<String, Any?> = hashMapOf(
        Fields.DATE to Dates.toIso(l.date),
        Fields.ORDER to l.order.toLong(),
        Fields.SUBJECT to l.subject,
        Fields.TITLE to l.title,
        Fields.COMPLETED to l.completed,
        Fields.COMPLETED_AT to l.completedAt?.toTimestamp(),
        Fields.PLANNED_MINUTES to l.plannedMinutes.toLong(),
        Fields.STUDIED_SECONDS to l.studiedSeconds,
        Fields.SOURCE to l.source.wire,
        Fields.CALENDAR_EVENT_ID to l.calendarEventId,
        Fields.UPDATED_AT to l.updatedAt.toTimestamp(),
    )

    /** Returns null for malformed documents (they are ignored rather than crashing the sync). */
    fun fromSnapshot(doc: DocumentSnapshot): Lecture? {
        val date = Dates.parseIsoOrNull(doc.getString(Fields.DATE)) ?: return null
        val title = doc.getString(Fields.TITLE)?.takeIf { it.isNotBlank() } ?: return null
        return Lecture(
            id = doc.id,
            date = date,
            order = (doc.getLong(Fields.ORDER) ?: 0L).toInt(),
            subject = doc.getString(Fields.SUBJECT) ?: Subject.OTHER.label,
            title = title,
            completed = doc.getBoolean(Fields.COMPLETED) ?: false,
            completedAt = doc.millis(Fields.COMPLETED_AT),
            plannedMinutes = (doc.getLong(Fields.PLANNED_MINUTES) ?: Lecture.DEFAULT_PLANNED_MINUTES.toLong()).toInt(),
            studiedSeconds = doc.getLong(Fields.STUDIED_SECONDS) ?: 0L,
            source = LectureSource.fromWire(doc.getString(Fields.SOURCE)),
            calendarEventId = doc.getString(Fields.CALENDAR_EVENT_ID),
            updatedAt = doc.millis(Fields.UPDATED_AT) ?: 0L,
        )
    }
}

object SessionMapper {

    fun toMap(s: StudySession): Map<String, Any?> = hashMapOf(
        Fields.LECTURE_ID to s.lectureId,
        Fields.STARTED_AT to s.startedAt.toTimestamp(),
        Fields.ENDED_AT to s.endedAt.toTimestamp(),
        Fields.PLANNED_MINUTES to s.plannedMinutes.toLong(),
        Fields.STUDIED_SECONDS to s.studiedSeconds,
    )

    fun fromSnapshot(doc: DocumentSnapshot): StudySession? {
        val lectureId = doc.getString(Fields.LECTURE_ID) ?: return null
        val startedAt = doc.millis(Fields.STARTED_AT) ?: return null
        return StudySession(
            id = doc.id,
            lectureId = lectureId,
            startedAt = startedAt,
            endedAt = doc.millis(Fields.ENDED_AT) ?: startedAt,
            plannedMinutes = (doc.getLong(Fields.PLANNED_MINUTES) ?: Lecture.DEFAULT_PLANNED_MINUTES.toLong()).toInt(),
            studiedSeconds = doc.getLong(Fields.STUDIED_SECONDS) ?: 0L,
        )
    }
}

object SettingsMapper {

    fun toMap(s: AppSettings, timeZone: String): Map<String, Any?> = hashMapOf(
        Fields.REMINDER_INTERVAL_HOURS to s.reminderIntervalHours.toLong(),
        Fields.REMINDER_START_HOUR to s.reminderStartHour.toLong(),
        Fields.REMINDER_END_HOUR to s.reminderEndHour.toLong(),
        Fields.REMINDERS_ENABLED to s.remindersEnabled,
        Fields.ALLOWED_APPS to s.allowedApps.sorted(),
        Fields.FOCUS_MODE_ENABLED to s.focusModeEnabled,
        Fields.CALENDAR_ID to (if (s.calendarConnected) s.calendarId else null),
        Fields.TIME_ZONE to timeZone,
        Fields.UPDATED_AT to s.updatedAt.toTimestamp(),
    )

    fun fromSnapshot(doc: DocumentSnapshot): RemoteSettings? {
        if (!doc.exists()) return null
        val defaults = AppSettings()
        @Suppress("UNCHECKED_CAST")
        val apps = (doc.get(Fields.ALLOWED_APPS) as? List<Any?>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
        return RemoteSettings(
            remindersEnabled = doc.getBoolean(Fields.REMINDERS_ENABLED) ?: defaults.remindersEnabled,
            reminderIntervalHours = (doc.getLong(Fields.REMINDER_INTERVAL_HOURS) ?: defaults.reminderIntervalHours.toLong()).toInt(),
            reminderStartHour = (doc.getLong(Fields.REMINDER_START_HOUR) ?: defaults.reminderStartHour.toLong()).toInt(),
            reminderEndHour = (doc.getLong(Fields.REMINDER_END_HOUR) ?: defaults.reminderEndHour.toLong()).toInt(),
            focusModeEnabled = doc.getBoolean(Fields.FOCUS_MODE_ENABLED) ?: defaults.focusModeEnabled,
            allowedApps = apps,
            calendarId = doc.getString(Fields.CALENDAR_ID),
            updatedAt = doc.millis(Fields.UPDATED_AT) ?: 0L,
        )
    }
}

/** The fields of `users/{uid}` that the app reads. */
data class UserDoc(
    val planSeeded: Boolean,
    val planSeededCount: Int,
    val fcmToken: String?,
)

object UserMapper {
    fun fromSnapshot(doc: DocumentSnapshot): UserDoc? {
        if (!doc.exists()) return null
        return UserDoc(
            planSeeded = doc.getBoolean(Fields.PLAN_SEEDED) ?: false,
            planSeededCount = (doc.getLong(Fields.PLAN_SEEDED_COUNT) ?: 0L).toInt(),
            fcmToken = doc.getString(Fields.FCM_TOKEN),
        )
    }
}
