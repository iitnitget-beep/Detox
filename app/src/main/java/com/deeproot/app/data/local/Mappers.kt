package com.deeproot.app.data.local

import com.deeproot.app.core.model.ActiveSession
import com.deeproot.app.core.model.Lecture
import com.deeproot.app.core.model.LectureSource
import com.deeproot.app.core.model.StudySession
import com.deeproot.app.core.sync.LocalLectureState
import com.deeproot.app.core.time.Dates
import com.deeproot.app.core.timer.TimerState
import java.time.LocalDate

fun LectureEntity.toDomain(): Lecture = Lecture(
    id = id,
    date = Dates.parseIsoOrNull(date) ?: LocalDate.MIN,
    order = order,
    subject = subject,
    title = title,
    completed = completed,
    completedAt = completedAt,
    plannedMinutes = plannedMinutes,
    studiedSeconds = studiedSeconds,
    source = LectureSource.fromWire(source),
    calendarEventId = calendarEventId,
    updatedAt = updatedAt,
)

fun Lecture.toEntity(dirty: Boolean, deleted: Boolean = false): LectureEntity = LectureEntity(
    id = id,
    date = Dates.toIso(date),
    order = order,
    subject = subject,
    title = title,
    completed = completed,
    completedAt = completedAt,
    plannedMinutes = plannedMinutes,
    studiedSeconds = studiedSeconds,
    source = source.wire,
    calendarEventId = calendarEventId,
    updatedAt = updatedAt,
    dirty = dirty,
    deleted = deleted,
)

fun LectureEntity.toLocalState(): LocalLectureState = LocalLectureState(toDomain(), dirty = dirty, deleted = deleted)

fun SessionEntity.toDomain(): StudySession = StudySession(
    id = id,
    lectureId = lectureId,
    startedAt = startedAt,
    endedAt = endedAt,
    plannedMinutes = plannedMinutes,
    studiedSeconds = studiedSeconds,
)

fun StudySession.toEntity(dirty: Boolean): SessionEntity = SessionEntity(
    id = id,
    lectureId = lectureId,
    startedAt = startedAt,
    endedAt = endedAt,
    plannedMinutes = plannedMinutes,
    studiedSeconds = studiedSeconds,
    dirty = dirty,
)

fun ActiveSessionEntity.toDomain(): ActiveSession = ActiveSession(
    sessionId = sessionId,
    lectureId = lectureId,
    lectureTitle = lectureTitle,
    subject = subject,
    startedAt = startedAt,
    plannedMinutes = plannedMinutes,
    timer = TimerState(
        plannedMillis = plannedMinutes.coerceAtLeast(0) * TimerState.MILLIS_PER_MINUTE,
        accumulatedMillis = accumulatedMillis.coerceAtLeast(0L),
        runningSinceMillis = runningSince,
    ),
    plannedEndNotified = plannedEndNotified,
)

fun ActiveSession.toEntity(): ActiveSessionEntity = ActiveSessionEntity(
    sessionId = sessionId,
    lectureId = lectureId,
    lectureTitle = lectureTitle,
    subject = subject,
    startedAt = startedAt,
    plannedMinutes = plannedMinutes,
    accumulatedMillis = timer.accumulatedMillis,
    runningSince = timer.runningSinceMillis,
    plannedEndNotified = plannedEndNotified,
)
