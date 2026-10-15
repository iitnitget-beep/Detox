package com.deeproot.app.core.sync

import com.deeproot.app.core.model.Lecture

/** A lecture as stored in Room, plus the local-only sync flags. */
data class LocalLectureState(
    val lecture: Lecture,
    /** Changed locally and not yet acknowledged by Firestore. */
    val dirty: Boolean,
    /** Soft-deleted locally; the Firestore delete is still pending. */
    val deleted: Boolean,
)

/** What to do after comparing local and remote data. */
data class MergePlan(
    /** Remote versions to write locally as clean rows. */
    val upserts: List<Lecture>,
    /** Local rows to remove (deleted remotely, or local tombstones already gone remotely). */
    val deleteLocal: List<String>,
    /** Local rows that are newer than (or missing from) the server and must be pushed. */
    val push: List<String>,
) {
    val isEmpty: Boolean get() = upserts.isEmpty() && deleteLocal.isEmpty() && push.isEmpty()
}

/**
 * Offline-first conflict resolution (last writer wins by `updatedAt`).
 *
 * Rules:
 * - Remote doc, no local row → take remote.
 * - Local row is dirty → keep local when `local.updatedAt >= remote.updatedAt` (it will be pushed), otherwise the
 *   newer remote version wins (also resurrecting a local tombstone).
 * - Local row is clean → take remote whenever it differs.
 * - Doc missing on the server (only when [remoteIsComplete]):
 *     - dirty, not deleted → push it (an offline edit survives a remote delete — e.g. a calendar event that was
 *       removed after you completed it offline);
 *     - dirty tombstone → drop it locally, the server already agrees;
 *     - clean → deleted on another device, drop it… unless the server has never been seeded
 *       ([missingRemoteMeansDeleted] = false), in which case it is pushed (first upload of the plan).
 *
 * Plan seeds use `updatedAt = 0`, so any real edit on any device always wins over pristine seed data.
 */
object LectureMerger {

    enum class Resolution { TAKE_REMOTE, KEEP_LOCAL, NOOP }

    fun resolve(local: LocalLectureState?, remote: Lecture): Resolution = when {
        local == null -> Resolution.TAKE_REMOTE
        local.dirty -> if (local.lecture.updatedAt >= remote.updatedAt) Resolution.KEEP_LOCAL else Resolution.TAKE_REMOTE
        local.deleted -> Resolution.TAKE_REMOTE
        local.lecture != remote -> Resolution.TAKE_REMOTE
        else -> Resolution.NOOP
    }

    fun merge(
        local: Map<String, LocalLectureState>,
        remote: Map<String, Lecture>,
        remoteIsComplete: Boolean,
        missingRemoteMeansDeleted: Boolean,
    ): MergePlan {
        val upserts = mutableListOf<Lecture>()
        val deleteLocal = mutableListOf<String>()
        val push = mutableListOf<String>()

        for ((id, remoteLecture) in remote) {
            when (resolve(local[id], remoteLecture)) {
                Resolution.TAKE_REMOTE -> upserts += remoteLecture
                Resolution.KEEP_LOCAL -> push += id
                Resolution.NOOP -> Unit
            }
        }

        if (remoteIsComplete) {
            for ((id, state) in local) {
                if (id in remote) continue
                when {
                    state.dirty && !state.deleted -> push += id
                    state.deleted -> deleteLocal += id
                    missingRemoteMeansDeleted -> deleteLocal += id
                    else -> push += id
                }
            }
        }
        return MergePlan(upserts = upserts, deleteLocal = deleteLocal, push = push)
    }
}
