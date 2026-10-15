package com.deeproot.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local copy of `users/{uid}/lectures/{id}` plus two local-only sync flags:
 * [dirty] (changed here, not yet acknowledged by Firestore) and [deleted] (soft-delete tombstone).
 */
@Entity(
    tableName = "lectures",
    indices = [Index("date"), Index("calendarEventId"), Index("dirty"), Index("source")],
)
data class LectureEntity(
    @PrimaryKey val id: String,
    /** ISO `yyyy-MM-dd`; lexicographic order equals chronological order. */
    val date: String,
    @ColumnInfo(name = "lecture_order") val order: Int,
    val subject: String,
    val title: String,
    val completed: Boolean,
    val completedAt: Long?,
    val plannedMinutes: Int,
    val studiedSeconds: Long,
    /** "plan" | "calendar" */
    val source: String,
    val calendarEventId: String?,
    /** Epoch millis of the last change; 0 for pristine plan seeds. */
    val updatedAt: Long,
    val dirty: Boolean = false,
    val deleted: Boolean = false,
)

/** Local copy of `users/{uid}/sessions/{id}`. Sessions are append-only. */
@Entity(
    tableName = "sessions",
    indices = [Index("lectureId"), Index("startedAt"), Index("dirty")],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val lectureId: String,
    val startedAt: Long,
    val endedAt: Long,
    val plannedMinutes: Int,
    val studiedSeconds: Long,
    val dirty: Boolean = false,
)

/**
 * The single running study session (row id is always [SINGLETON_ID]).
 * Only timestamps are stored so the timer is exact after process death or reboot.
 */
@Entity(tableName = "active_session")
data class ActiveSessionEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val sessionId: String,
    val lectureId: String,
    val lectureTitle: String,
    val subject: String,
    val startedAt: Long,
    val plannedMinutes: Int,
    val accumulatedMillis: Long,
    /** Wall-clock start of the current running segment; null while paused. */
    val runningSince: Long?,
    val plannedEndNotified: Boolean,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

/** Result row for the overall progress query. */
data class ProgressCounts(
    val total: Int,
    val completed: Int,
)
