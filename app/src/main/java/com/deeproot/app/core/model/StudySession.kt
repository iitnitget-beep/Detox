package com.deeproot.app.core.model

import com.deeproot.app.core.timer.TimerState

/** A finished study session. Mirrors `users/{uid}/sessions/{sessionId}`. */
data class StudySession(
    val id: String,
    val lectureId: String,
    val startedAt: Long,
    val endedAt: Long,
    val plannedMinutes: Int,
    val studiedSeconds: Long,
)

/**
 * The session that is currently running (at most one). Persisted locally so the timer survives
 * process death: only timestamps are stored, never a ticking counter (see [TimerState]).
 */
data class ActiveSession(
    val sessionId: String,
    val lectureId: String,
    val lectureTitle: String,
    val subject: String,
    val startedAt: Long,
    val plannedMinutes: Int,
    val timer: TimerState,
    val plannedEndNotified: Boolean,
) {
    val displayName: String get() = "$subject – $lectureTitle"
}
