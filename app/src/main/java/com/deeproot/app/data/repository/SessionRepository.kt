package com.deeproot.app.data.repository

import androidx.room.withTransaction
import com.deeproot.app.core.model.ActiveSession
import com.deeproot.app.core.model.Lecture
import com.deeproot.app.core.model.StudySession
import com.deeproot.app.core.time.TimeSource
import com.deeproot.app.core.timer.TimerState
import com.deeproot.app.data.local.ActiveSessionDao
import com.deeproot.app.data.local.AppDatabase
import com.deeproot.app.data.local.LectureDao
import com.deeproot.app.data.local.SessionDao
import com.deeproot.app.data.local.toDomain
import com.deeproot.app.data.local.toEntity
import com.deeproot.app.data.sync.SyncTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface StartResult {
    data class Started(val session: ActiveSession) : StartResult
    data class AlreadyRunning(val session: ActiveSession) : StartResult
    data object LectureNotFound : StartResult
}

data class FinishResult(
    val lectureId: String,
    val lectureName: String,
    val studiedSeconds: Long,
    val completed: Boolean,
)

/**
 * The study timer's persistence. The running session is a single Room row holding only timestamps, so the timer is
 * exact after the process is killed. Finishing writes a `sessions` row and adds the time to the lecture, both
 * marked dirty for upload.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val db: AppDatabase,
    private val activeDao: ActiveSessionDao,
    private val sessionDao: SessionDao,
    private val lectureDao: LectureDao,
    private val time: TimeSource,
    private val syncTrigger: SyncTrigger,
) {
    private val mutex = Mutex()

    val active: Flow<ActiveSession?> = activeDao.observe().map { it?.toDomain() }.distinctUntilChanged()

    val sessions: Flow<List<StudySession>> = sessionDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun current(): ActiveSession? = activeDao.get()?.toDomain()

    suspend fun start(lectureId: String, plannedMinutes: Int): StartResult = mutex.withLock {
        val existing = activeDao.get()?.toDomain()
        if (existing != null) return@withLock StartResult.AlreadyRunning(existing)
        val now = time.nowMillis()
        val minutes = plannedMinutes.coerceIn(1, LectureRepository.MAX_PLANNED_MINUTES)
        val session = db.withTransaction {
            val row = lectureDao.getById(lectureId)?.takeIf { !it.deleted } ?: return@withTransaction null
            if (row.plannedMinutes != minutes) {
                lectureDao.upsertAll(listOf(row.copy(plannedMinutes = minutes, updatedAt = now, dirty = true)))
            }
            val lecture: Lecture = row.toDomain()
            val active = ActiveSession(
                sessionId = UUID.randomUUID().toString(),
                lectureId = lecture.id,
                lectureTitle = lecture.title,
                subject = lecture.subject,
                startedAt = now,
                plannedMinutes = minutes,
                timer = TimerState.start(now, minutes),
                plannedEndNotified = false,
            )
            activeDao.upsert(active.toEntity())
            active
        } ?: return@withLock StartResult.LectureNotFound
        syncTrigger.requestPush()
        StartResult.Started(session)
    }

    suspend fun pause(): ActiveSession? = update { it.copy(timer = it.timer.pause(time.nowMillis())) }

    suspend fun resume(): ActiveSession? = update { it.copy(timer = it.timer.resume(time.nowMillis())) }

    suspend fun markPlannedEndNotified(): ActiveSession? = update { it.copy(plannedEndNotified = true) }

    /**
     * Ends the session. Saves the studied time on the lecture and as a session document; marks the lecture completed
     * when [markCompleted] is true. Returns null when no session was running.
     */
    suspend fun finish(markCompleted: Boolean): FinishResult? = mutex.withLock {
        val now = time.nowMillis()
        val result = db.withTransaction {
            val active = activeDao.get()?.toDomain() ?: return@withTransaction null
            val studiedSeconds = active.timer.studiedSeconds(now).coerceIn(0L, LectureRepository.MAX_STUDIED_SECONDS)
            if (studiedSeconds > 0) {
                val session = StudySession(
                    id = active.sessionId,
                    lectureId = active.lectureId,
                    startedAt = active.startedAt,
                    endedAt = maxOf(now, active.startedAt),
                    plannedMinutes = active.plannedMinutes,
                    studiedSeconds = studiedSeconds,
                )
                sessionDao.upsert(session.toEntity(dirty = true))
            }
            val row = lectureDao.getById(active.lectureId)
            var completed = false
            if (row != null && !row.deleted) {
                completed = row.completed || markCompleted
                lectureDao.upsertAll(
                    listOf(
                        row.copy(
                            studiedSeconds = (row.studiedSeconds + studiedSeconds).coerceAtMost(LectureRepository.MAX_STUDIED_SECONDS),
                            completed = completed,
                            completedAt = when {
                                row.completed -> row.completedAt
                                markCompleted -> now
                                else -> null
                            },
                            updatedAt = now,
                            dirty = true,
                        ),
                    ),
                )
            }
            activeDao.clear()
            FinishResult(
                lectureId = active.lectureId,
                lectureName = active.displayName,
                studiedSeconds = studiedSeconds,
                completed = completed,
            )
        }
        if (result != null) syncTrigger.requestPush()
        result
    }

    private suspend fun update(transform: (ActiveSession) -> ActiveSession): ActiveSession? = mutex.withLock {
        val current = activeDao.get()?.toDomain() ?: return@withLock null
        val next = transform(current)
        if (next != current) activeDao.upsert(next.toEntity())
        next
    }
}
