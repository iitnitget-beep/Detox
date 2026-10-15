package com.deeproot.app.core.sync

import com.deeproot.app.core.model.Lecture
import com.deeproot.app.core.model.LectureSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LectureMergerTest {

    private fun lecture(id: String, updatedAt: Long, completed: Boolean = false) = Lecture(
        id = id,
        date = LocalDate.of(2026, 10, 15),
        order = 1,
        subject = "Physics",
        title = "Centre of Mass 1",
        completed = completed,
        completedAt = if (completed) updatedAt else null,
        plannedMinutes = 120,
        studiedSeconds = 0,
        source = LectureSource.PLAN,
        calendarEventId = null,
        updatedAt = updatedAt,
    )

    private fun local(l: Lecture, dirty: Boolean = false, deleted: Boolean = false) = LocalLectureState(l, dirty, deleted)

    @Test
    fun `remote only is inserted`() {
        val plan = LectureMerger.merge(emptyMap(), mapOf("a" to lecture("a", 5)), remoteIsComplete = true, missingRemoteMeansDeleted = true)
        assertEquals(listOf("a"), plan.upserts.map { it.id })
    }

    @Test
    fun `newer dirty local wins and is pushed`() {
        val plan = LectureMerger.merge(
            mapOf("a" to local(lecture("a", 10, completed = true), dirty = true)),
            mapOf("a" to lecture("a", 5)),
            remoteIsComplete = true,
            missingRemoteMeansDeleted = true,
        )
        assertEquals(listOf("a"), plan.push)
        assertTrue(plan.upserts.isEmpty())
    }

    @Test
    fun `newer remote beats an older dirty local`() {
        val plan = LectureMerger.merge(
            mapOf("a" to local(lecture("a", 5), dirty = true)),
            mapOf("a" to lecture("a", 10, completed = true)),
            remoteIsComplete = true,
            missingRemoteMeansDeleted = true,
        )
        assertEquals(listOf("a"), plan.upserts.map { it.id })
        assertTrue(plan.upserts.single().completed)
    }

    @Test
    fun `clean local follows remote changes`() {
        val plan = LectureMerger.merge(
            mapOf("a" to local(lecture("a", 5))),
            mapOf("a" to lecture("a", 6, completed = true)),
            remoteIsComplete = true,
            missingRemoteMeansDeleted = true,
        )
        assertEquals(1, plan.upserts.size)
    }

    @Test
    fun `identical data is a no-op`() {
        val plan = LectureMerger.merge(
            mapOf("a" to local(lecture("a", 5))),
            mapOf("a" to lecture("a", 5)),
            remoteIsComplete = true,
            missingRemoteMeansDeleted = true,
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `seed data never overwrites real progress`() {
        // Local seed (updatedAt = 0) vs remote progress from another device.
        val plan = LectureMerger.merge(
            mapOf("a" to local(lecture("a", 0))),
            mapOf("a" to lecture("a", 1_000, completed = true)),
            remoteIsComplete = true,
            missingRemoteMeansDeleted = true,
        )
        assertTrue(plan.upserts.single().completed)
    }

    @Test
    fun `missing remote doc deletes a clean local row once the server is seeded`() {
        val plan = LectureMerger.merge(mapOf("a" to local(lecture("a", 5))), emptyMap(), remoteIsComplete = true, missingRemoteMeansDeleted = true)
        assertEquals(listOf("a"), plan.deleteLocal)
    }

    @Test
    fun `missing remote doc is uploaded when the server was never seeded`() {
        val plan = LectureMerger.merge(mapOf("a" to local(lecture("a", 0))), emptyMap(), remoteIsComplete = true, missingRemoteMeansDeleted = false)
        assertEquals(listOf("a"), plan.push)
        assertTrue(plan.deleteLocal.isEmpty())
    }

    @Test
    fun `offline edit survives a remote delete`() {
        val plan = LectureMerger.merge(
            mapOf("a" to local(lecture("a", 9, completed = true), dirty = true)),
            emptyMap(),
            remoteIsComplete = true,
            missingRemoteMeansDeleted = true,
        )
        assertEquals(listOf("a"), plan.push)
    }

    @Test
    fun `local tombstone is purged when the server no longer has the doc`() {
        val plan = LectureMerger.merge(
            mapOf("a" to local(lecture("a", 9), dirty = true, deleted = true)),
            emptyMap(),
            remoteIsComplete = true,
            missingRemoteMeansDeleted = true,
        )
        assertEquals(listOf("a"), plan.deleteLocal)
    }

    @Test
    fun `local delete newer than remote is pushed, older delete is undone`() {
        val newerDelete = LectureMerger.merge(
            mapOf("a" to local(lecture("a", 20), dirty = true, deleted = true)),
            mapOf("a" to lecture("a", 10)),
            remoteIsComplete = true,
            missingRemoteMeansDeleted = true,
        )
        assertEquals(listOf("a"), newerDelete.push)

        val olderDelete = LectureMerger.merge(
            mapOf("a" to local(lecture("a", 10), dirty = true, deleted = true)),
            mapOf("a" to lecture("a", 20, completed = true)),
            remoteIsComplete = true,
            missingRemoteMeansDeleted = true,
        )
        assertEquals(listOf("a"), olderDelete.upserts.map { it.id })
    }

    @Test
    fun `partial snapshots never delete`() {
        val plan = LectureMerger.merge(mapOf("a" to local(lecture("a", 5))), emptyMap(), remoteIsComplete = false, missingRemoteMeansDeleted = true)
        assertTrue(plan.isEmpty)
    }
}
