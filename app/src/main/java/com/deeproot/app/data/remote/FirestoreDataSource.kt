package com.deeproot.app.data.remote

import com.deeproot.app.core.model.AppSettings
import com.deeproot.app.core.model.Lecture
import com.deeproot.app.core.model.StudySession
import com.deeproot.app.data.prefs.RemoteSettings
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A Firestore write was not acknowledged in time (usually offline); it stays queued in the SDK and dirty in Room. */
class WriteTimeoutException : IOException("Firestore write timed out")

/** The aggregate maintained by the onLectureCompleted Cloud Function. */
data class CloudSummary(
    val totalLectures: Long,
    val completedLectures: Long,
    val studiedSeconds: Long,
)

/** Result of a lectures snapshot listener event. */
sealed interface LecturesSnapshot {
    data class Data(val lectures: Map<String, Lecture>, val fromCache: Boolean, val hasPendingWrites: Boolean) : LecturesSnapshot
    data class Error(val error: Exception) : LecturesSnapshot
}

/**
 * Thin wrapper around Firestore. Writes are awaited with a timeout because Firestore tasks never complete
 * while offline (the SDK queues them); on timeout the caller simply keeps the rows dirty and retries later.
 */
@Singleton
class FirestoreDataSource @Inject constructor(
    private val db: FirebaseFirestore,
) {
    private fun user(uid: String): DocumentReference = db.collection(FirestorePaths.USERS).document(uid)
    private fun lectures(uid: String): CollectionReference = user(uid).collection(FirestorePaths.LECTURES)
    private fun sessions(uid: String): CollectionReference = user(uid).collection(FirestorePaths.SESSIONS)
    private fun settings(uid: String): DocumentReference =
        user(uid).collection(FirestorePaths.SETTINGS).document(FirestorePaths.SETTINGS_DOC)

    fun statsSummary(uid: String): DocumentReference =
        user(uid).collection(FirestorePaths.STATS).document(FirestorePaths.STATS_SUMMARY_DOC)

    /**
     * Awaits a write with a timeout. Uses withTimeoutOrNull + a plain IOException on purpose: a
     * TimeoutCancellationException is a CancellationException and would silently cancel the calling coroutine.
     */
    private suspend fun <T> Task<T>.awaitWrite(): T {
        var completed = false
        val result = withTimeoutOrNull(WRITE_TIMEOUT_MS) { await().also { completed = true } }
        if (!completed) throw WriteTimeoutException()
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    // ---- lectures ----

    /** Full server read of the user's lectures. Throws when offline. */
    suspend fun fetchLectures(uid: String): Map<String, Lecture> {
        val snapshot = lectures(uid).get(Source.SERVER).await()
        return snapshot.documents.mapNotNull { LectureMapper.fromSnapshot(it) }.associateBy { it.id }
    }

    suspend fun fetchLecture(uid: String, id: String): Lecture? {
        val doc = lectures(uid).document(id).get(Source.SERVER).await()
        return if (doc.exists()) LectureMapper.fromSnapshot(doc) else null
    }

    fun listenLectures(uid: String, onEvent: (LecturesSnapshot) -> Unit): ListenerRegistration =
        lectures(uid).addSnapshotListener(Dispatchers.IO.asExecutor(), MetadataChanges.INCLUDE) { snapshot, error ->
            when {
                error != null -> onEvent(LecturesSnapshot.Error(error))
                snapshot != null -> onEvent(
                    LecturesSnapshot.Data(
                        lectures = snapshot.documents.mapNotNull { LectureMapper.fromSnapshot(it) }.associateBy { it.id },
                        fromCache = snapshot.metadata.isFromCache,
                        hasPendingWrites = snapshot.metadata.hasPendingWrites(),
                    ),
                )
            }
        }

    /** Writes up to [MAX_BATCH] upserts/deletes atomically. */
    suspend fun commitLectures(uid: String, upserts: List<Lecture>, deletes: List<String>) {
        require(upserts.size + deletes.size <= MAX_BATCH) { "Batch too large" }
        val batch = db.batch()
        upserts.forEach { batch.set(lectures(uid).document(it.id), LectureMapper.toMap(it)) }
        deletes.forEach { batch.delete(lectures(uid).document(it)) }
        batch.commit().awaitWrite()
    }

    suspend fun setLecture(uid: String, lecture: Lecture) {
        lectures(uid).document(lecture.id).set(LectureMapper.toMap(lecture)).awaitWrite()
    }

    suspend fun deleteLecture(uid: String, id: String) {
        lectures(uid).document(id).delete().awaitWrite()
    }

    /** Live users/{uid}/stats/summary (null when missing or unreadable). */
    fun statsSummaryFlow(uid: String): Flow<CloudSummary?> = callbackFlow {
        val registration = statsSummary(uid).addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || !snapshot.exists()) {
                trySend(null)
            } else {
                trySend(
                    CloudSummary(
                        totalLectures = snapshot.getLong("totalLectures") ?: 0L,
                        completedLectures = snapshot.getLong("completedLectures") ?: 0L,
                        studiedSeconds = snapshot.getLong("studiedSeconds") ?: 0L,
                    ),
                )
            }
        }
        awaitClose { registration.remove() }
    }

    // ---- sessions ----

    suspend fun commitSessions(uid: String, items: List<StudySession>) {
        require(items.size <= MAX_BATCH) { "Batch too large" }
        val batch = db.batch()
        items.forEach { batch.set(sessions(uid).document(it.id), SessionMapper.toMap(it)) }
        batch.commit().awaitWrite()
    }

    suspend fun fetchSessions(uid: String): List<StudySession> =
        sessions(uid).get(Source.SERVER).await().documents.mapNotNull { SessionMapper.fromSnapshot(it) }

    // ---- user + settings ----

    suspend fun fetchUser(uid: String): UserDoc? = UserMapper.fromSnapshot(user(uid).get(Source.SERVER).await())

    /** Creates or updates `users/{uid}` with device metadata. */
    suspend fun upsertUser(uid: String, isNew: Boolean, timeZone: String, appVersion: String, nowMillis: Long) {
        val data = hashMapOf<String, Any?>(
            Fields.TIME_ZONE to timeZone,
            Fields.PLATFORM to "android",
            Fields.APP_VERSION to appVersion,
            Fields.UPDATED_AT to nowMillis.toTimestamp(),
        )
        if (isNew) data[Fields.CREATED_AT] = nowMillis.toTimestamp()
        user(uid).set(data, SetOptions.merge()).awaitWrite()
    }

    suspend fun setPlanSeeded(uid: String, count: Int) {
        user(uid).set(
            hashMapOf(Fields.PLAN_SEEDED to true, Fields.PLAN_SEEDED_COUNT to count.toLong()),
            SetOptions.merge(),
        ).awaitWrite()
    }

    suspend fun setFcmToken(uid: String, token: String?) {
        user(uid).set(hashMapOf(Fields.FCM_TOKEN to token), SetOptions.merge()).awaitWrite()
    }

    /** Lets the scheduled Cloud Function skip a push when a local reminder was already shown. */
    suspend fun setLastLocalReminder(uid: String, atMillis: Long) {
        user(uid).set(hashMapOf(Fields.LAST_LOCAL_REMINDER_AT to atMillis.toTimestamp()), SetOptions.merge()).awaitWrite()
    }

    suspend fun fetchSettings(uid: String): RemoteSettings? =
        SettingsMapper.fromSnapshot(settings(uid).get(Source.SERVER).await())

    suspend fun pushSettings(uid: String, settings: AppSettings, timeZone: String) {
        settings(uid).set(SettingsMapper.toMap(settings, timeZone)).awaitWrite()
    }

    companion object {
        /** Firestore allows 500 operations per batch; stay well below it. */
        const val MAX_BATCH = 400
        private const val WRITE_TIMEOUT_MS = 20_000L
    }
}
