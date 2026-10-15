package com.deeproot.app.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Date helpers shared by Room, Firestore mapping and the UI. Dates are stored as ISO `yyyy-MM-dd`. */
object Dates {
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun toIso(date: LocalDate): String = date.format(ISO)

    fun parseIsoOrNull(text: String?): LocalDate? = try {
        if (text.isNullOrBlank()) null else LocalDate.parse(text.trim(), ISO)
    } catch (e: DateTimeParseException) {
        null
    }

    fun localDateOf(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    fun localDateTimeOf(epochMillis: Long, zone: ZoneId): LocalDateTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()

    fun startOfDayMillis(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** "Thu, 15 Oct" */
    fun shortLabel(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(DateTimeFormatter.ofPattern("EEE, d MMM", locale))

    /** "Thursday, 15 October 2026" */
    fun longLabel(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale))

    /** "Mon" — used under the weekly chart bars. */
    fun weekdayShort(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(DateTimeFormatter.ofPattern("EEE", locale))

    /** "08:00" */
    fun hourLabel(hour: Int): String = String.format(Locale.US, "%02d:00", hour.coerceIn(0, 24))
}
