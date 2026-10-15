package com.deeproot.app.data.seed

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.deeproot.app.core.model.Lecture
import com.deeproot.app.core.model.LectureSource
import com.deeproot.app.core.plan.ParsedPlan
import com.deeproot.app.core.plan.PlanParser
import com.deeproot.app.data.local.AppDatabase
import com.deeproot.app.data.local.LectureDao
import com.deeproot.app.data.local.toEntity
import com.deeproot.app.data.prefs.SettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Facts about the bundled plan that the UI needs (rest days, range, verification). */
data class PlanInfo(
    val lectureCount: Int,
    val lectureLineCount: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
    val noLectureDates: Set<LocalDate>,
    val verified: Boolean,
)

sealed interface SeedState {
    data object Idle : SeedState
    data object Seeding : SeedState
    data class Done(val info: PlanInfo, val insertedNow: Int) : SeedState
    data class Failed(val message: String) : SeedState
}

/**
 * One-time Kotlin seeder: parses `assets/lecture_plan.md` and inserts every lecture into Room (offline, instant).
 * Upload to Firestore happens through [com.deeproot.app.data.sync.SyncManager] (preferably via the `seedLectures`
 * Cloud Function, which creates the same documents with the same ids server-side).
 *
 * Verification: the number of parsed lectures must equal the number of `Subject: Title` lines in the file, and after
 * the first seed the number of plan rows in Room must equal the parsed count.
 */
@Singleton
class PlanSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val lectureDao: LectureDao,
    private val prefs: SettingsDataStore,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SeedState>(SeedState.Idle)
    val state: StateFlow<SeedState> = _state.asStateFlow()

    @Volatile
    private var cachedPlan: ParsedPlan? = null

    suspend fun plan(): ParsedPlan = cachedPlan ?: withContext(Dispatchers.IO) {
        val text = context.assets.open(PLAN_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        PlanParser.parse(text).also { cachedPlan = it }
    }

    suspend fun seedIfNeeded(): SeedState = mutex.withLock {
        val current = _state.value
        if (current is SeedState.Done) return current
        _state.value = SeedState.Seeding
        val result = try {
            seed()
        } catch (e: Exception) {
            Log.e(TAG, "Seeding failed", e)
            SeedState.Failed(e.message ?: e.javaClass.simpleName)
        }
        _state.value = result
        result
    }

    private suspend fun seed(): SeedState {
        val plan = plan()
        val info = PlanInfo(
            lectureCount = plan.lectures.size,
            lectureLineCount = plan.lectureLineCount,
            firstDate = plan.firstDate,
            lastDate = plan.lastDate,
            noLectureDates = plan.noLectureDates.toSet(),
            verified = plan.isVerified,
        )
        if (!plan.isVerified) {
            return SeedState.Failed(
                "Plan file check failed: parsed ${plan.lectures.size} of ${plan.lectureLineCount} lecture lines. " +
                    plan.warnings.take(3).joinToString("; "),
            )
        }
        val flags = prefs.currentFlags()
        if (flags.planSeedVersion >= PLAN_VERSION) {
            return SeedState.Done(info, insertedNow = 0)
        }
        val isFirstSeed = flags.planSeedVersion == 0
        val entities = plan.lectures.map { planned ->
            Lecture(
                id = planned.id,
                date = planned.date,
                order = planned.order,
                subject = planned.subject.label,
                title = planned.title,
                completed = false,
                completedAt = null,
                plannedMinutes = Lecture.DEFAULT_PLANNED_MINUTES,
                studiedSeconds = 0L,
                source = LectureSource.PLAN,
                calendarEventId = null,
                // 0 = pristine seed: any real edit (on any device) wins the last-writer-wins merge.
                updatedAt = 0L,
                // After a plan upgrade the new lectures must be uploaded, otherwise the next reconcile with an
                // already-seeded server would treat them as "deleted on another device".
            ).toEntity(dirty = !isFirstSeed)
        }
        val (inserted, planRows) = db.withTransaction {
            val ids = entities.chunked(CHUNK).flatMap { lectureDao.insertIgnore(it) }
            ids.count { it != -1L } to lectureDao.countPlanLectures()
        }
        if (isFirstSeed && planRows < plan.lectures.size) {
            return SeedState.Failed(
                "Seed verification failed: $planRows plan lectures in the database, expected ${plan.lectures.size}.",
            )
        }
        prefs.setPlanSeeded(PLAN_VERSION, plan.lectures.size)
        Log.i(TAG, "Seeded plan v$PLAN_VERSION: inserted $inserted, plan rows $planRows, expected ${plan.lectures.size}")
        return SeedState.Done(info, insertedNow = inserted)
    }

    companion object {
        private const val TAG = "PlanSeeder"
        const val PLAN_ASSET = "lecture_plan.md"

        /** Bump when `lecture_plan.md` changes so new lectures are inserted on existing installs. */
        const val PLAN_VERSION = 1
        private const val CHUNK = 200
    }
}
