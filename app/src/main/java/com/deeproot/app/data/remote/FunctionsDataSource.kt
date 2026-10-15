package com.deeproot.app.data.remote

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Response of the `seedLectures` callable. */
data class SeedResult(
    val expected: Int,
    val created: Int,
    val alreadyPresent: Int,
    val totalPlanDocs: Int,
    val verified: Boolean,
)

/** Response of the `syncCalendar` callable. */
data class CalendarSyncResult(
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
    val keptCompleted: Int,
    val events: Int,
)

/** Calls the HTTPS callable Cloud Functions in [com.deeproot.app.BuildConfig.FUNCTIONS_REGION]. */
@Singleton
class FunctionsDataSource @Inject constructor(
    private val functions: FirebaseFunctions,
) {
    /** Creates any missing plan lectures for the signed-in user server-side and verifies the count. */
    suspend fun seedLectures(): SeedResult {
        val result = functions.getHttpsCallable("seedLectures")
            .apply { setTimeout(120, TimeUnit.SECONDS) }
            .call(hashMapOf("client" to "android"))
            .await()
        val data = result.getData() as? Map<*, *> ?: throw IllegalStateException("Unexpected seedLectures response")
        return SeedResult(
            expected = data.int("expected"),
            created = data.int("created"),
            alreadyPresent = data.int("alreadyPresent"),
            totalPlanDocs = data.int("totalPlanDocs"),
            verified = data["verified"] == true,
        )
    }

    /**
     * Server-side calendar sync. The OAuth access token (scope calendar.readonly) is passed per call and never
     * stored on the server.
     */
    suspend fun syncCalendar(accessToken: String, calendarId: String, timeZone: String): CalendarSyncResult {
        val result = functions.getHttpsCallable("syncCalendar")
            .apply { setTimeout(120, TimeUnit.SECONDS) }
            .call(hashMapOf("accessToken" to accessToken, "calendarId" to calendarId, "timeZone" to timeZone))
            .await()
        val data = result.getData() as? Map<*, *> ?: throw IllegalStateException("Unexpected syncCalendar response")
        return CalendarSyncResult(
            inserted = data.int("inserted"),
            updated = data.int("updated"),
            deleted = data.int("deleted"),
            keptCompleted = data.int("keptCompleted"),
            events = data.int("events"),
        )
    }

    private fun Map<*, *>.int(key: String): Int = (this[key] as? Number)?.toInt() ?: 0

    companion object {
        /** Errors that mean "the function is not deployed / not reachable" rather than a real failure. */
        fun isUnavailable(e: Throwable): Boolean {
            val code = (e as? FirebaseFunctionsException)?.code ?: return false
            return code in setOf(
                FirebaseFunctionsException.Code.NOT_FOUND,
                FirebaseFunctionsException.Code.UNIMPLEMENTED,
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.INTERNAL,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
                FirebaseFunctionsException.Code.UNKNOWN,
            )
        }
    }
}
