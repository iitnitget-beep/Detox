package com.deeproot.app.core.model

import java.time.LocalDate

/** Where a lecture came from. [wire] is the value stored in Firestore (`source` field). */
enum class LectureSource(val wire: String) {
    PLAN("plan"),
    CALENDAR("calendar");

    companion object {
        fun fromWire(value: String?): LectureSource = entries.firstOrNull { it.wire == value } ?: PLAN
    }
}

/**
 * Domain model for one lecture. Mirrors `users/{uid}/lectures/{lectureId}`.
 * Timestamps are epoch milliseconds; [date] is the local calendar day the lecture is planned for.
 */
data class Lecture(
    val id: String,
    val date: LocalDate,
    val order: Int,
    val subject: String,
    val title: String,
    val completed: Boolean,
    val completedAt: Long?,
    val plannedMinutes: Int,
    val studiedSeconds: Long,
    val source: LectureSource,
    val calendarEventId: String?,
    val updatedAt: Long,
) {
    val subjectEnum: Subject get() = Subject.fromLabel(subject)

    /** "Physics – Rotation 2" */
    val displayName: String get() = "$subject – $title"

    companion object {
        const val DEFAULT_PLANNED_MINUTES = 120
    }
}
