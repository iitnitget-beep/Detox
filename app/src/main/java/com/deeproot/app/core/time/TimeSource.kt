package com.deeproot.app.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Abstraction over the wall clock so time-dependent logic can be unit tested. */
interface TimeSource {
    fun nowMillis(): Long
    fun zone(): ZoneId

    fun today(): LocalDate = Instant.ofEpochMilli(nowMillis()).atZone(zone()).toLocalDate()
    fun nowLocal(): LocalDateTime = Instant.ofEpochMilli(nowMillis()).atZone(zone()).toLocalDateTime()
}

/** Real device clock and time zone. */
class SystemTimeSource : TimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}
