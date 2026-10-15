package com.deeproot.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LectureDao {

    @Query("SELECT * FROM lectures WHERE date = :date AND deleted = 0 ORDER BY lecture_order, title")
    fun observeByDate(date: String): Flow<List<LectureEntity>>

    @Query("SELECT * FROM lectures WHERE date < :today AND completed = 0 AND deleted = 0 ORDER BY date, lecture_order")
    fun observeBacklog(today: String): Flow<List<LectureEntity>>

    @Query("SELECT COUNT(*) AS total, COALESCE(SUM(completed), 0) AS completed FROM lectures WHERE deleted = 0")
    fun observeProgress(): Flow<ProgressCounts>

    @Query("SELECT * FROM lectures WHERE deleted = 0 ORDER BY date, lecture_order")
    fun observeAll(): Flow<List<LectureEntity>>

    @Query("SELECT * FROM lectures WHERE id = :id AND deleted = 0 LIMIT 1")
    fun observeById(id: String): Flow<LectureEntity?>

    @Query("SELECT COUNT(*) FROM lectures WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>

    @Query("SELECT * FROM lectures WHERE date <= :today AND completed = 0 AND deleted = 0 ORDER BY date, lecture_order")
    suspend fun getPendingUpTo(today: String): List<LectureEntity>

    @Query("SELECT * FROM lectures WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): LectureEntity?

    @Query("SELECT * FROM lectures WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<LectureEntity>

    @Query("SELECT * FROM lectures")
    suspend fun getAll(): List<LectureEntity>

    @Query("SELECT * FROM lectures WHERE dirty = 1")
    suspend fun getDirty(): List<LectureEntity>

    @Query("SELECT COUNT(*) FROM lectures WHERE dirty = 1 AND source = 'plan'")
    suspend fun countDirtyPlan(): Int

    @Query("SELECT * FROM lectures WHERE source = 'calendar' AND deleted = 0")
    suspend fun getCalendarLectures(): List<LectureEntity>

    @Query("SELECT COUNT(*) FROM lectures WHERE source = 'plan'")
    suspend fun countPlanLectures(): Int

    @Upsert
    suspend fun upsertAll(lectures: List<LectureEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(lectures: List<LectureEntity>): List<Long>

    /** Clears the dirty flag only if the row was not modified again while the push was in flight. */
    @Query("UPDATE lectures SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markClean(id: String, updatedAt: Long)

    @Query("UPDATE lectures SET dirty = 1 WHERE id IN (:ids)")
    suspend fun markDirty(ids: List<String>)

    @Query("UPDATE lectures SET dirty = 1")
    suspend fun markAllDirty()

    /** Removes a tombstone after Firestore confirmed the delete (unless it was restored meanwhile). */
    @Query("DELETE FROM lectures WHERE id = :id AND deleted = 1 AND updatedAt = :updatedAt")
    suspend fun purgeTombstone(id: String, updatedAt: Long)

    @Query("DELETE FROM lectures WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT COUNT(*) FROM sessions WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(sessions: List<SessionEntity>): List<Long>

    @Query("SELECT * FROM sessions WHERE dirty = 1")
    suspend fun getDirty(): List<SessionEntity>

    @Query("UPDATE sessions SET dirty = 0 WHERE id IN (:ids)")
    suspend fun markClean(ids: List<String>)

    @Query("UPDATE sessions SET dirty = 1")
    suspend fun markAllDirty()
}

@Dao
interface ActiveSessionDao {

    @Query("SELECT * FROM active_session WHERE id = 1 LIMIT 1")
    fun observe(): Flow<ActiveSessionEntity?>

    @Query("SELECT * FROM active_session WHERE id = 1 LIMIT 1")
    suspend fun get(): ActiveSessionEntity?

    @Upsert
    suspend fun upsert(session: ActiveSessionEntity)

    @Query("DELETE FROM active_session")
    suspend fun clear()
}
