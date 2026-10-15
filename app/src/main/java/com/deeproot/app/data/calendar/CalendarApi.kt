package com.deeproot.app.data.calendar

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/** Minimal Google Calendar API v3 client (read-only; scope calendar.readonly). Base URL: https://www.googleapis.com/ */
interface CalendarApi {

    @GET("calendar/v3/users/me/calendarList")
    suspend fun calendarList(
        @Header("Authorization") authorization: String,
        @Query("minAccessRole") minAccessRole: String = "reader",
        @Query("maxResults") maxResults: Int = 250,
        @Query("pageToken") pageToken: String? = null,
    ): CalendarListResponse

    @GET("calendar/v3/calendars/{calendarId}/events")
    suspend fun events(
        @Header("Authorization") authorization: String,
        @Path("calendarId") calendarId: String,
        @Query("timeMin") timeMin: String,
        @Query("timeMax") timeMax: String,
        @Query("singleEvents") singleEvents: Boolean = true,
        @Query("orderBy") orderBy: String = "startTime",
        @Query("showDeleted") showDeleted: Boolean = false,
        @Query("maxResults") maxResults: Int = 250,
        @Query("pageToken") pageToken: String? = null,
    ): EventsResponse
}

@Serializable
data class CalendarListResponse(
    val items: List<CalendarListEntry> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class CalendarListEntry(
    val id: String,
    val summary: String? = null,
    val summaryOverride: String? = null,
    val primary: Boolean = false,
    val accessRole: String? = null,
    val backgroundColor: String? = null,
)

@Serializable
data class EventsResponse(
    val items: List<CalendarEvent> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class CalendarEvent(
    val id: String? = null,
    val status: String? = null,
    val summary: String? = null,
    val start: EventTime? = null,
    val end: EventTime? = null,
)

@Serializable
data class EventTime(
    /** All-day events: yyyy-MM-dd */
    val date: String? = null,
    /** Timed events: RFC 3339 */
    val dateTime: String? = null,
    val timeZone: String? = null,
)
