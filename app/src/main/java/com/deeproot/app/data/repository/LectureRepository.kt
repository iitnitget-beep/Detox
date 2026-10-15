package com.deeproot.app.data.repository

import androidx.room.withTransaction
import com.deeproot.app.core.model.Lecture
import com.deeproot.app.core.time.Dates
import com.deeproot.app.core.time.TimeSource
import com.deeproot.app.data.local.AppDatabase
import com.deeproot.app.data.local.LectureDao
import com.deeproot.app.data.local.ProgressCounts
import com.deeproot.app.data.local.toDomain
import com.deeproot.app.data.local.toEntity
import com.deeproot.app.data.sync.SyncTrigger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first access to lectures. Reads come from Room; every write goes to Room first (marked dirty, with a
 * fresh `updatedAt`) and is then uploaded by the sync layer.
 */
@Singleton
class LectureRepository @Inject constructor(
    private val db: AppDatabase,
    private val dao: LectureDao,
    private val time: TimeSource,
    private val syncTrigger: SyncTrigger,
) {
    fun observeDay(date: LocalDate): Flow<List<Lecture>> =
        dao.observeByDate(Dates.toIso(date)).map { rows -> rows.map { it.toDomain() } }

    /** Uncompleted lectures planned before [today], oldest first. */
    fun observeBacklog(today: LocalDate): Flow<List<Lecture>> =
        dao.observeBacklog(Dates.toIso(today)).map { rows -> rows.map { it.toDomain() } }

    fun observeProgress(): Flow<ProgressCounts> = dao.observeProgress().distinctUntilChanged()

    fun observeAll(): Flow<List<Lecture>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeLecture(id: String): Flow<Lecture?> = dao.observeById(id).map { it?.toDomain() }

    suspend fun getLecture(id: String): Lecture? = dao.getById(id)?.takeIf { !it.deleted }?.toDomain()

    /** Pending lectures for today and earlier (today first is decided by the caller). */
    suspend fun pendingUpTo(today: LocalDate): List<Lecture> = dao.getPendingUpTo(Dates.toIso(today)).map { it.toDomain() }

    /**
     * Marks lectures completed / not completed. Returns the previous versions of the rows that actually changed,
     * so the caller can offer Undo via [restore].
     */
    suspend fun setCompleted(ids: Collection<String>, completed: Boolean): List<Lecture> {
        if (ids.isEmpty()) return emptyList()
        val now = time.nowMillis()
        val previous = db.withTransaction {
            val changed = dao.getByIds(ids.toList()).filter { !it.deleted && it.completed != completed }
            if (changed.isNotEmpty()) {
                dao.upsertAll(
                    changed.map {
                        it.copy(
                            completed = completed,
                            completedAt = if (completed) now else null,
                            updatedAt = now,
                            dirty = true,
                        )
                    },
                )
            }
            changed.map { it.toDomain() }
        }
        if (previous.isNotEmpty()) syncTrigger.requestPush()
        return previous
    }

    /** Soft-deletes lectures (tombstones until Firestore confirms). Returns snapshots for Undo via [restore]. */
    suspend fun delete(ids: Collection<String>): List<Lecture> {
        if (ids.isEmpty()) return emptyList()
        val now = time.nowMillis()
        val snapshots = db.withTransaction {
            val rows = dao.getByIds(ids.toList()).filter { !it.deleted }
            if (rows.isNotEmpty()) dao.upsertAll(rows.map { it.copy(deleted = true, dirty = true, updatedAt = now) })
            rows.map { it.toDomain() }
        }
        if (snapshots.isNotEmpty()) syncTrigger.requestPush()
        return snapshots
    }

    /**
     * Undo: writes the given versions back (as a new change, so it wins over the change being undone everywhere).
     * Works for both completion changes and deletes, even if the delete already reached Firestore.
     */
    suspend fun restore(previous: List<Lecture>) {
        if (previous.isEmpty()) return
        val now = time.nowMillis()
        db.withTransaction {
            dao.upsertAll(previous.map { it.copy(updatedAt = now).toEntity(dirty = true, deleted = false) })
        }
        syncTrigger.requestPush()
    }

    /** Adds studied time from a finished focus session and optionally marks the lecture completed. */
    suspend fun recordStudy(lectureId: String, addSeconds: Long, markCompleted: Boolean): Lecture? {
        val now = time.nowMillis()
        val updated = db.withTransaction {
            val row = dao.getById(lectureId)?.takeIf { !it.deleted } ?: return@withTransaction null
            val next = row.copy(
                studiedSeconds = (row.studiedSeconds + addSeconds.coerceAtLeast(0L)).coerceAtMost(MAX_STUDIED_SECONDS),
                completed = row.completed || markCompleted,
                completedAt = when {
                    row.completed -> row.completedAt
                    markCompleted -> now
                    else -> null
                },
                updatedAt = now,
                dirty = true,
            )
            dao.upsertAll(listOf(next))
            next.toDomain()
        }
        if (updated != null) syncTrigger.requestPush()
        return updated
    }

    /** Changes the planned duration chosen in the "How long will this lecture take?" dialog. */
    suspend fun setPlannedMinutes(lectureId: String, minutes: Int) {
        val now = time.nowMillis()
        val changed = db.withTransaction {
            val row = dao.getById(lectureId)?.takeIf { !it.deleted } ?: return@withTransaction false
            val clamped = minutes.coerceIn(1, MAX_PLANNED_MINUTES)
            if (row.plannedMinutes == clamped) return@withTransaction false
            dao.upsertAll(listOf(row.copy(plannedMinutes = clamped, updatedAt = now, dirty = true)))
            true
        }
        if (changed) syncTrigger.requestPush()
    }

    companion object {
        /** Mirrors the limits in firestore.rules. */
        const val MAX_STUDIED_SECONDS = 864_000L
        const val MAX_PLANNED_MINUTES = 1_440
    }
}
