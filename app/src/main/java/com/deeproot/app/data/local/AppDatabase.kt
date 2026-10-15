package com.deeproot.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [LectureEntity::class, SessionEntity::class, ActiveSessionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lectureDao(): LectureDao
    abstract fun sessionDao(): SessionDao
    abstract fun activeSessionDao(): ActiveSessionDao

    companion object {
        const val NAME = "lecture_tracker.db"
    }
}
