package com.deeproot.app.data.sync

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.withTransaction
import com.deeproot.app.BuildConfig
import com.deeproot.app.core.model.Lecture
import com.deeproot.app.core.sync.LectureMerger
import com.deeproot.app.core.sync.MergePlan
import com.deeproot.app.core.time.TimeSource
import com.deeproot.app.data.auth.AuthRepository
import com.deeproot.app.data.local.AppDatabase
import com.deeproot.app.data.local.LectureDao
import com.deeproot.app.data.local.LectureEntity
import com.deeproot.app.data.local.SessionDao
import com.deeproot.app.data.local.toDomain
import com.deeproot.app.data.local.toEntity
import com.deeproot.app.data.local.toLocalState
import com.deeproot.app.data.prefs.SettingsDataStore
import com.deeproot.app.data.remote.FirestoreDataSource
import com.deeproot.app.data.remote.FunctionsDataSource
import com.deeproot.app.data.remote.LecturesSnapshot
import com.deeproot.app.data.remote.SeedResult
import com.deeproot.app.data.remote.WriteTimeoutException
import com.deeproot.app.data.seed.PlanSeeder
import com.deeproot.app.data.seed.SeedState
import com.deeproot.app.di.ApplicationScope
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncPhase { IDLE, SYNCING }

data class SyncStatus(
    val signedIn: Boolean = false,
    val uid: String? = null,
    val isAnonymous: Boolean = true,
    val email: String? = null,
    val displayName: String? = null,
    val online: Boolean = true,
    val phase: SyncPhase = SyncPhase.IDLE,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val pendingLectures: Int = 0,
    val pendingSessions: Int = 0,
    val remoteSeeded: Boolean = false,
    val lastSeedResult: SeedResult? = null,
) {
    val pendingChanges: Int get() = pendingLectures + pendingSessions
}

enum class PushOutcome { DONE, RETRY, OFFLINE, NOT_SIGNED_IN }

/**
 * Offline-first sync between Room (what the UI reads) and Firestore.
 *
 * - Local writes go to Room first with `dirty = true` and are pushed in batches (≤400 writes) — immediately while
 *   the app runs, otherwise by [SyncWorker] when the network is back.
 * - On every app start (per signed-in user) a bootstrap does: ensure `users/{uid}` → seed server via the
 *   `seedLectures` function if needed (device upload as fallback) → full server read → merge → push.
 * - While the app is in the foreground a snapshot listener keeps Room in sync with changes from other devices and
 *   the Cloud Functions (calendar sync).
 * - Conflicts: last writer wins by `updatedAt` ([LectureMerger]); the security rules reject stale overwrites, and a
 *   rejected write is resolved by taking the server version.
 */
@OptIn(FlowPreview::class)
@Singleton
class SyncManager @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
    private val auth: AuthRepository,
    private val remote: FirestoreDataSource,
    private val functions: FunctionsDataSource,
    private val messaging: FirebaseMessaging,
    private val db: AppDatabase,
    private val lectureDao: LectureDao,
    private val sessionDao: SessionDao,
    private val prefs: SettingsDataStore,
    private val seeder: PlanSeeder,
    private val network: NetworkMonitor,
    private val trigger: SyncTrigger,
    private val time: TimeSource,
) {
    private val started = AtomicBoolean(false)
    private val bootstrapMutex = Mutex()
    private val pushMutex = Mutex()
    private val mergeMutex = Mutex()

    @Volatile
    private var bootstrappedUid: String? = null

    private val listenerLock = Any()
    private var listener: ListenerRegistration? = null
    private var listenerUid: String? = null

    /** elapsedRealtime of the last completed push; snapshots received before it may predate our own writes. */
    private val lastPushCompletedAt = AtomicLong(0L)

    private val phase = MutableStateFlow(SyncPhase.IDLE)
    private val lastError = MutableStateFlow<String?>(null)
    private val lastSeedResult = MutableStateFlow<SeedResult?>(null)

    private val pendingCounts = combine(lectureDao.observeDirtyCount(), sessionDao.observeDirtyCount()) { l, s -> l to s }
    private val runtime = combine(phase, lastError, lastSeedResult) { p, e, r -> Triple(p, e, r) }

    val status: StateFlow<SyncStatus> = combine(
        auth.user,
        network.isOnline,
        prefs.flags,
        pendingCounts,
        runtime,
    ) { user, online, flags, counts, rt ->
        SyncStatus(
            signedIn = user != null,
            uid = user?.uid,
            isAnonymous = user?.isAnonymous ?: true,
            email = user?.email,
            displayName = user?.displayName,
            online = online,
            phase = rt.first,
            lastSyncAt = flags.lastSyncAt,
            lastError = rt.second,
            pendingLectures = counts.first,
            pendingSessions = counts.second,
            remoteSeeded = user != null && flags.remoteSeededUid == user.uid,
            lastSeedResult = rt.third,
        )
    }.stateIn(scope, SharingStarted.Eagerly, SyncStatus())

    /** Called once from [com.deeproot.app.LectureTrackerApp.onCreate]. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { seeder.seedIfNeeded() }
        scope.launch {
            combine(
                auth.user.map { it?.uid }.distinctUntilChanged(),
                network.isOnline,
                appInForeground(),
            ) { uid, online, foreground -> Triple(uid, online, foreground) }
                .collectLatest { (uid, online, foreground) ->
                    if (!online) {
                        detachListener()
                        return@collectLatest
                    }
                    if (uid == null) {
                        // A new uid arrives through auth.user and restarts this block.
                        signInAnonymously()
                        return@collectLatest
                    }
                    if (foreground) {
                        runSafely("Sync") { bootstrap(uid, force = false) }
                        attachListener(uid)
                    } else {
                        detachListener()
                    }
                }
        }
        scope.launch {
            trigger.requests.debounce(PUSH_DEBOUNCE_MS).collect { pushDirty() }
        }
    }

    /** "Sync now" from Settings / pull-to-refresh. */
    suspend fun syncNow(): Result<Unit> = runCatching {
        if (!network.isOnlineNow()) {
            throw IllegalStateException("You're offline. Your changes are saved on this phone and will sync automatically.")
        }
        val uid = auth.ensureSignedIn()
        bootstrap(uid, force = true)
        attachListener(uid)
    }.onFailure { e ->
        if (e is CancellationException) throw e
        lastError.value = describe(e)
    }

    /** Uploads dirty rows. Used by [SyncWorker] and the in-process fast path. */
    suspend fun pushDirty(): PushOutcome {
        val uid = auth.currentUid ?: return PushOutcome.NOT_SIGNED_IN
        if (!network.isOnlineNow()) return PushOutcome.OFFLINE
        return try {
            if (pushAll(uid)) PushOutcome.DONE else PushOutcome.RETRY
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Push failed", e)
            lastError.value = describe(e)
            PushOutcome.RETRY
        }
    }

    /** Signs in anonymously if needed (used by the worker when the app was never opened online). */
    suspend fun ensureSignedIn(): Boolean = runCatching { auth.ensureSignedIn() }.isSuccess

    /** The user switched Firebase accounts (Google sign-in collision): everything is re-merged under the new uid. */
    fun onAccountSwitched() {
        bootstrappedUid = null
        detachListener()
    }

    fun onNewFcmToken(token: String) {
        scope.launch {
            prefs.setFcmToken(token)
            val uid = auth.currentUid ?: return@launch
            runCatching { remote.setFcmToken(uid, token) }.onFailure { Log.w(TAG, "FCM token upload failed", it) }
        }
    }

    // ------------------------------------------------------------------ bootstrap

    private suspend fun signInAnonymously() {
        try {
            auth.ensureSignedIn()
            lastError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Anonymous sign-in failed", e)
            lastError.value = describe(e)
        }
    }

    private suspend fun bootstrap(uid: String, force: Boolean) = bootstrapMutex.withLock {
        if (!force && bootstrappedUid == uid) {
            pushAll(uid)
            return@withLock
        }
        phase.value = SyncPhase.SYNCING
        try {
            val seedState = seeder.seedIfNeeded()
            check(seedState is SeedState.Done) { (seedState as? SeedState.Failed)?.message ?: "Local plan not ready" }

            val flags = prefs.currentFlags()
            if (flags.lastSyncedUid != null && flags.lastSyncedUid != uid) {
                // Different account than last time (e.g. signed in with Google on a fresh install): upload everything.
                db.withTransaction {
                    lectureDao.markAllDirty()
                    sessionDao.markAllDirty()
                }
                prefs.markSettingsDirty()
            }

            val userDoc = remote.fetchUser(uid)
            remote.upsertUser(
                uid = uid,
                isNew = userDoc == null,
                timeZone = time.zone().id,
                appVersion = BuildConfig.VERSION_NAME,
                nowMillis = time.nowMillis(),
            )
            var seeded = userDoc?.planSeeded == true
            if (!seeded) seeded = seedViaFunction()

            val remoteLectures = remote.fetchLectures(uid)
            merge(remoteLectures, remoteIsComplete = true, missingRemoteMeansDeleted = seeded)
            pushAll(uid)

            if (!seeded && lectureDao.countDirtyPlan() == 0) {
                // Fallback path: the device uploaded the plan itself.
                remote.setPlanSeeded(uid, seeder.plan().lectures.size)
                seeded = true
            }
            if (seeded) prefs.setRemoteSeededUid(uid)

            syncSettings(uid)
            pullSessions(uid)
            pushAll(uid)
            registerFcmToken(uid)

            prefs.setLastSynced(uid, time.nowMillis())
            bootstrappedUid = uid
            lastError.value = null
        } finally {
            phase.value = SyncPhase.IDLE
        }
    }

    private suspend fun seedViaFunction(): Boolean = try {
        val result = functions.seedLectures()
        lastSeedResult.value = result
        Log.i(TAG, "seedLectures → $result")
        result.verified
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "seedLectures failed; the device will upload the plan instead", e)
        false
    }

    // ------------------------------------------------------------------ merge

    private suspend fun merge(
        remoteLectures: Map<String, Lecture>,
        remoteIsComplete: Boolean,
        missingRemoteMeansDeleted: Boolean,
    ): MergePlan = mergeMutex.withLock {
        db.withTransaction {
            val local = lectureDao.getAll().associate { it.id to it.toLocalState() }
            val plan = LectureMerger.merge(local, remoteLectures, remoteIsComplete, missingRemoteMeansDeleted)
            plan.upserts.chunked(SQL_CHUNK).forEach { chunk -> lectureDao.upsertAll(chunk.map { it.toEntity(dirty = false) }) }
            plan.deleteLocal.chunked(SQL_CHUNK).forEach { lectureDao.deleteByIds(it) }
            plan.push.chunked(SQL_CHUNK).forEach { lectureDao.markDirty(it) }
            plan
        }
    }

    // ------------------------------------------------------------------ listener

    private fun attachListener(uid: String) = synchronized(listenerLock) {
        if (listenerUid == uid && listener != null) return@synchronized
        listener?.remove()
        listenerUid = uid
        listener = remote.listenLectures(uid) { event ->
            when (event) {
                is LecturesSnapshot.Data -> {
                    // Cached or locally-pending snapshots are not authoritative; a server snapshot always follows.
                    if (event.fromCache || event.hasPendingWrites) return@listenLectures
                    val receivedAt = SystemClock.elapsedRealtime()
                    scope.launch { onServerSnapshot(uid, event.lectures, receivedAt) }
                }
                is LecturesSnapshot.Error -> {
                    Log.w(TAG, "Lecture listener failed", event.error)
                    lastError.value = describe(event.error)
                    synchronized(listenerLock) {
                        if (listenerUid == uid) {
                            listener = null
                            listenerUid = null
                        }
                    }
                }
            }
        }
    }

    private fun detachListener() = synchronized(listenerLock) {
        listener?.remove()
        listener = null
        listenerUid = null
    }

    private suspend fun onServerSnapshot(uid: String, lectures: Map<String, Lecture>, receivedAt: Long) {
        if (auth.currentUid != uid) return
        runSafely("Merge") {
            // A snapshot that arrived before our latest push finished may not contain those writes yet:
            // apply its newer versions only, never deletions.
            val complete = receivedAt > lastPushCompletedAt.get()
            val seeded = prefs.currentFlags().remoteSeededUid == uid
            val plan = merge(lectures, remoteIsComplete = complete, missingRemoteMeansDeleted = seeded)
            if (plan.push.isNotEmpty()) trigger.requestPush()
        }
    }

    // ------------------------------------------------------------------ push

    /** Returns true when nothing is left to upload. */
    private suspend fun pushAll(uid: String): Boolean = pushMutex.withLock {
        var rounds = 0
        var pushedLectures = false
        while (rounds++ < MAX_PUSH_ROUNDS) {
            val dirty = lectureDao.getDirty()
            if (dirty.isEmpty()) break
            pushLectureRows(uid, dirty)
            pushedLectures = true
        }
        if (pushedLectures) lastPushCompletedAt.set(SystemClock.elapsedRealtime())
        pushSessions(uid)
        pushSettingsIfDirty(uid)
        lectureDao.getDirty().isEmpty() && sessionDao.getDirty().isEmpty()
    }

    private suspend fun pushLectureRows(uid: String, rows: List<LectureEntity>) {
        for (chunk in rows.chunked(FirestoreDataSource.MAX_BATCH)) {
            try {
                remote.commitLectures(
                    uid = uid,
                    upserts = chunk.filterNot { it.deleted }.map { it.toDomain() },
                    deletes = chunk.filter { it.deleted }.map { it.id },
                )
                markPushed(chunk)
            } catch (e: FirebaseFirestoreException) {
                if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
                // One stale/invalid document rejects the whole batch: retry one by one to isolate it.
                pushIndividually(uid, chunk)
            }
        }
    }

    private suspend fun pushIndividually(uid: String, rows: List<LectureEntity>) {
        for (row in rows) {
            try {
                if (row.deleted) remote.deleteLecture(uid, row.id) else remote.setLecture(uid, row.toDomain())
                markPushed(listOf(row))
            } catch (e: FirebaseFirestoreException) {
                if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
                resolveRejected(uid, row)
            }
        }
    }

    /** The server rejected a write: it holds a newer version (last-writer-wins rule) or the data is invalid. */
    private suspend fun resolveRejected(uid: String, row: LectureEntity) {
        val server = runCatching { remote.fetchLecture(uid, row.id) }.getOrNull()
        db.withTransaction {
            val current = lectureDao.getById(row.id) ?: return@withTransaction
            if (current.updatedAt != row.updatedAt) return@withTransaction // edited again meanwhile; retried next round
            if (server != null && server.updatedAt >= row.updatedAt) {
                lectureDao.upsertAll(listOf(server.toEntity(dirty = false)))
            } else {
                Log.e(TAG, "Firestore rejected lecture ${row.id}; keeping it on this device only")
                if (row.deleted) lectureDao.purgeTombstone(row.id, row.updatedAt) else lectureDao.markClean(row.id, row.updatedAt)
            }
        }
    }

    private suspend fun markPushed(rows: List<LectureEntity>) = db.withTransaction {
        rows.forEach { row ->
            if (row.deleted) lectureDao.purgeTombstone(row.id, row.updatedAt) else lectureDao.markClean(row.id, row.updatedAt)
        }
    }

    private suspend fun pushSessions(uid: String) {
        val dirty = sessionDao.getDirty()
        for (chunk in dirty.chunked(FirestoreDataSource.MAX_BATCH)) {
            try {
                remote.commitSessions(uid, chunk.map { it.toDomain() })
                sessionDao.markClean(chunk.map { it.id })
            } catch (e: FirebaseFirestoreException) {
                if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
                for (session in chunk) {
                    try {
                        remote.commitSessions(uid, listOf(session.toDomain()))
                    } catch (inner: FirebaseFirestoreException) {
                        if (inner.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw inner
                        Log.e(TAG, "Firestore rejected session ${session.id}; keeping it on this device only")
                    }
                    sessionDao.markClean(listOf(session.id))
                }
            }
        }
    }

    private suspend fun pullSessions(uid: String) {
        val sessions = remote.fetchSessions(uid)
        sessions.chunked(SQL_CHUNK).forEach { chunk -> sessionDao.insertIgnore(chunk.map { it.toEntity(dirty = false) }) }
    }

    // ------------------------------------------------------------------ settings + FCM

    private suspend fun syncSettings(uid: String) {
        val remoteSettings = remote.fetchSettings(uid)
        val local = prefs.current()
        val dirty = prefs.currentFlags().settingsDirty
        when {
            remoteSettings == null -> pushSettings(uid)
            remoteSettings.updatedAt > local.updatedAt -> prefs.applyRemote(remoteSettings)
            dirty || local.updatedAt > remoteSettings.updatedAt -> pushSettings(uid)
        }
    }

    private suspend fun pushSettingsIfDirty(uid: String) {
        if (prefs.currentFlags().settingsDirty) pushSettings(uid)
    }

    private suspend fun pushSettings(uid: String) {
        val settings = prefs.current()
        remote.pushSettings(uid, settings, time.zone().id)
        prefs.markSettingsClean(settings.updatedAt)
    }

    private suspend fun registerFcmToken(uid: String) {
        val token = runCatching { messaging.token.await() }.getOrNull() ?: return
        prefs.setFcmToken(token)
        runCatching { remote.setFcmToken(uid, token) }.onFailure { Log.w(TAG, "FCM token upload failed", it) }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun runSafely(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed", e)
            lastError.value = describe(e)
        }
    }

    private fun appInForeground(): Flow<Boolean> = callbackFlow {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = LifecycleEventObserver { _, _ ->
            trySend(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        lifecycle.addObserver(observer)
        awaitClose { lifecycle.removeObserver(observer) }
    }.flowOn(Dispatchers.Main.immediate).distinctUntilChanged()

    private fun describe(e: Throwable): String = when {
        e is FirebaseAuthException &&
            (e.errorCode == "ERROR_OPERATION_NOT_ALLOWED" || e.errorCode == "ERROR_ADMIN_RESTRICTED_OPERATION") ->
            "Anonymous sign-in is disabled. Enable it in Firebase Console → Authentication → Sign-in method."
        e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            "Firestore denied access. Deploy firestore.rules (see docs/SETUP_FIREBASE.md)."
        e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.UNAVAILABLE ->
            "Firestore is unreachable right now. Changes stay on this phone and sync later."
        e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.NOT_FOUND ->
            "Firestore database not found. Create it in Firebase Console → Firestore Database."
        e is WriteTimeoutException ->
            "The server did not respond in time. Changes stay on this phone and sync later."
        else -> e.message ?: e.javaClass.simpleName
    }

    private companion object {
        const val TAG = "SyncManager"
        const val SQL_CHUNK = 500
        const val MAX_PUSH_ROUNDS = 3
        const val PUSH_DEBOUNCE_MS = 400L
    }
}
