package com.deeproot.app.core.stats

import com.deeproot.app.core.model.Lecture
import com.deeproot.app.core.model.LectureSource
import com.deeproot.app.core.model.StudySession
import com.deeproot.app.core.model.Subject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DayTotal(val date: LocalDate, val studiedSeconds: Long, val completed: Int)

data class SubjectTotal(val subject: String, val total: Int, val completed: Int, val studiedSeconds: Long) {
    val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total.toFloat()
}

data class StatsSnapshot(
    val todayStudiedSeconds: Long,
    val todayCompleted: Int,
    val week: List<DayTotal>,
    val streakDays: Int,
    val subjects: List<SubjectTotal>,
    val totalLectures: Int,
    val totalCompleted: Int,
    val totalStudiedSeconds: Long,
) {
    val isEmpty: Boolean get() = totalLectures == 0
}

/** Pure statistics derived from local data (works offline). */
object StatsCalculator {

    fun compute(lectures: List<Lecture>, sessions: List<StudySession>, today: LocalDate, zone: ZoneId): StatsSnapshot {
        val studied = studiedByDay(sessions, zone)
        val completed = completedByDay(lectures, zone)
        return StatsSnapshot(
            todayStudiedSeconds = studied[today] ?: 0L,
            todayCompleted = completed[today] ?: 0,
            week = lastDays(today, 7, studied, completed),
            streakDays = streak(completed.keys, restDays(lectures), today),
            subjects = perSubject(lectures),
            totalLectures = lectures.size,
            totalCompleted = lectures.count { it.completed },
            totalStudiedSeconds = sessions.sumOf { it.studiedSeconds },
        )
    }

    /** Study time per local day (a session counts for the day it started). */
    fun studiedByDay(sessions: List<StudySession>, zone: ZoneId): Map<LocalDate, Long> =
        sessions.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
            .mapValues { (_, list) -> list.sumOf { it.studiedSeconds } }

    /** Completed lectures per local day of completion. */
    fun completedByDay(lectures: List<Lecture>, zone: ZoneId): Map<LocalDate, Int> =
        lectures.filter { it.completed && it.completedAt != null }
            .groupingBy { Instant.ofEpochMilli(it.completedAt!!).atZone(zone).toLocalDate() }
            .eachCount()

    /** The [days] days ending with [today], oldest first. */
    fun lastDays(today: LocalDate, days: Int, studied: Map<LocalDate, Long>, completed: Map<LocalDate, Int>): List<DayTotal> =
        (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            DayTotal(date, studied[date] ?: 0L, completed[date] ?: 0)
        }

    /**
     * Plan days with no scheduled lectures ("NO LECTURE") inside the plan range. They neither extend nor break
     * a streak.
     */
    fun restDays(lectures: List<Lecture>): Set<LocalDate> {
        val planDates = lectures.filter { it.source == LectureSource.PLAN }.map { it.date }
        val first = planDates.minOrNull() ?: return emptySet()
        val last = planDates.maxOrNull() ?: return emptySet()
        val scheduled = lectures.mapTo(hashSetOf()) { it.date }
        val rest = mutableSetOf<LocalDate>()
        var day = first
        while (!day.isAfter(last)) {
            if (day !in scheduled) rest += day
            day = day.plusDays(1)
        }
        return rest
    }

    /**
     * Consecutive days (ending today) with at least one completed lecture. Today not being done yet does not
     * break the streak; rest days are skipped.
     */
    fun streak(activeDays: Set<LocalDate>, restDays: Set<LocalDate>, today: LocalDate): Int {
        var day = if (today in activeDays) today else today.minusDays(1)
        var count = 0
        var guard = 0
        while (guard++ < 5000) {
            when {
                day in activeDays -> {
                    count++
                    day = day.minusDays(1)
                }
                day in restDays -> day = day.minusDays(1)
                else -> break
            }
        }
        return count
    }

    /** Totals per subject in plan order, followed by any other subjects (e.g. "Other"). */
    fun perSubject(lectures: List<Lecture>): List<SubjectTotal> {
        val grouped = lectures.groupBy { it.subject }
        val order = Subject.planSubjects.map { it.label } + (grouped.keys - Subject.planSubjects.map { it.label }.toSet()).sorted()
        return order.mapNotNull { subject ->
            val list = grouped[subject] ?: return@mapNotNull null
            SubjectTotal(
                subject = subject,
                total = list.size,
                completed = list.count { it.completed },
                studiedSeconds = list.sumOf { it.studiedSeconds },
            )
        }
    }
}
