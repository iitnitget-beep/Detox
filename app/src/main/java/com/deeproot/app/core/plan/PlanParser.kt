package com.deeproot.app.core.plan

import com.deeproot.app.core.model.Subject
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

/** One lecture from the plan file. */
data class PlannedLecture(
    val date: LocalDate,
    /** 1-based position within the day, in file order. */
    val order: Int,
    val subject: Subject,
    val title: String,
) {
    val id: String get() = PlanIds.planLectureId(date, order)
}

/** Result of parsing a plan file, including the numbers needed to verify the import. */
data class ParsedPlan(
    val lectures: List<PlannedLecture>,
    /** Dates explicitly marked "NO LECTURE". */
    val noLectureDates: List<LocalDate>,
    /** Every line that looked like `Subject: Title`, counted independently of placement. */
    val lectureLineCount: Int,
    val warnings: List<String>,
) {
    val lectureDates: Set<LocalDate> get() = lectures.mapTo(sortedSetOf()) { it.date }
    val firstDate: LocalDate? get() = lectures.minOfOrNull { it.date }
    val lastDate: LocalDate? get() = lectures.maxOfOrNull { it.date }

    /** True when every lecture line was placed under a date (i.e. the parsed count matches the file). */
    val isVerified: Boolean get() = lectures.size == lectureLineCount && lectureLineCount > 0
}

/** Deterministic document IDs shared by the Kotlin seeder and the `seedLectures` Cloud Function. */
object PlanIds {
    /** `plan_2026-10-15_01` */
    fun planLectureId(date: LocalDate, order: Int): String =
        String.format(Locale.US, "plan_%s_%02d", date.toString(), order)
}

/**
 * Parses the day-by-day lecture plan.
 *
 * Accepted structure (Markdown or plain text):
 * ```
 * ## 2026-10-15 (Thu)          <- date heading (ISO, "15 Oct 2026", "Oct 15, 2026", "15/10/2026", ...)
 * Physics: Centre of Mass 1    <- "Subject: Title N"
 * - **Maths:** Matrix 2        <- bullets / bold are tolerated
 * ## 2026-10-28 (Wed)
 * NO LECTURE                   <- the date has no lectures (may also appear on the heading line)
 * ```
 * Lines before the first heading (titles, notes) are ignored.
 */
object PlanParser {

    private val LECTURE_LINE = Regex(
        """^(Physics|Organic(?:\s+Chem(?:istry)?)?|Physical\s+Chem(?:istry)?|Inorganic(?:\s+Chem(?:istry)?)?|Maths?|Mathematics)\s*:\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val UNKNOWN_SUBJECT_LINE = Regex("""^([A-Za-z][A-Za-z ]{1,30})\s*:\s*\S.*$""")
    private val NO_LECTURE = Regex("""\bno\s+lectures?\b""", RegexOption.IGNORE_CASE)

    private val ISO_DATE = Regex("""(?<!\d)(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)""")
    private val DAY_MONTH_YEAR =
        Regex("""(?<!\d)(\d{1,2})(?:st|nd|rd|th)?[\s\-]+([A-Za-z]{3,9})\.?,?[\s\-]+(\d{4})(?!\d)""")
    private val MONTH_DAY_YEAR =
        Regex("""(?<![A-Za-z])([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})(?!\d)""")
    private val NUMERIC_DMY = Regex("""(?<!\d)(\d{1,2})[/.](\d{1,2})[/.](\d{4})(?!\d)""")

    private val WEEKDAY = Regex(
        """\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DAY_NUMBER = Regex("""\bday\s*\d+\b""", RegexOption.IGNORE_CASE)
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
    private val LEADING_MARKUP = Regex("""^\s*(?:#{1,6}\s*|[-*+•]\s+|\d+[.)]\s+)?""")
    private val INLINE_MARKUP = Regex("""\*\*|__|`""")
    private val WHITESPACE = Regex("""\s+""")

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    fun parse(text: String): ParsedPlan {
        val lectures = mutableListOf<PlannedLecture>()
        val noLecture = linkedSetOf<LocalDate>()
        val warnings = mutableListOf<String>()
        val nextOrder = mutableMapOf<LocalDate, Int>()
        var currentDate: LocalDate? = null
        var lectureLines = 0

        text.lineSequence().forEachIndexed { index, rawLine ->
            val lineNo = index + 1
            val line = clean(rawLine)
            if (line.isEmpty()) return@forEachIndexed

            val lectureMatch = LECTURE_LINE.matchEntire(line)
            if (lectureMatch != null) {
                lectureLines++
                val date = currentDate
                if (date == null) {
                    warnings += "Line $lineNo: lecture before any date heading was skipped: \"$line\""
                    return@forEachIndexed
                }
                val subject = Subject.parseOrNull(lectureMatch.groupValues[1])
                val title = lectureMatch.groupValues[2].trim()
                if (subject == null || title.isEmpty()) {
                    warnings += "Line $lineNo: could not read lecture \"$line\""
                    return@forEachIndexed
                }
                val order = nextOrder.getOrDefault(date, 0) + 1
                nextOrder[date] = order
                lectures += PlannedLecture(date = date, order = order, subject = subject, title = title)
                return@forEachIndexed
            }

            val heading = parseHeading(line)
            if (heading != null) {
                currentDate = heading.first
                if (heading.second) noLecture += heading.first
                return@forEachIndexed
            }

            val date = currentDate ?: return@forEachIndexed // preamble text before the first heading
            if (NO_LECTURE.containsMatchIn(line)) {
                noLecture += date
            } else if (UNKNOWN_SUBJECT_LINE.matches(line)) {
                warnings += "Line $lineNo: unknown subject, ignored: \"$line\""
            }
        }

        val datesWithLectures = lectures.mapTo(hashSetOf()) { it.date }
        val conflicting = noLecture.filter { it in datesWithLectures }
        conflicting.forEach { warnings += "$it is marked NO LECTURE but also lists lectures; the lectures were kept." }

        return ParsedPlan(
            lectures = lectures.sortedWith(compareBy<PlannedLecture>({ it.date }, { it.order })),
            noLectureDates = noLecture.filter { it !in datesWithLectures }.sorted(),
            lectureLineCount = lectureLines,
            warnings = warnings,
        )
    }

    /** Strips Markdown decoration so "- **Physics:** Rotation 2" becomes "Physics: Rotation 2". */
    private fun clean(raw: String): String =
        raw.replace(INLINE_MARKUP, "")
            .replace(LEADING_MARKUP, "")
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * Returns (date, isNoLecture) when the line is a date heading: after removing the date itself, weekday names,
     * "Day N" and an optional "NO LECTURE" marker, nothing meaningful may remain. This keeps titles such as
     * "Lecture Plan — 15 Oct 2026 to 9 Jan 2027" from being treated as headings.
     */
    private fun parseHeading(line: String): Pair<LocalDate, Boolean>? {
        val found = findDate(line) ?: return null
        val rest = line.removeRange(found.second)
            .replace(WEEKDAY, " ")
            .replace(DAY_NUMBER, " ")
            .replace(NO_LECTURE, " ")
            .replace(NON_ALNUM, "")
        if (rest.isNotEmpty()) return null
        return found.first to NO_LECTURE.containsMatchIn(line)
    }

    private fun findDate(line: String): Pair<LocalDate, IntRange>? {
        ISO_DATE.find(line)?.let { m ->
            val (y, mo, d) = m.destructured
            safeDate(y.toInt(), mo.toInt(), d.toInt())?.let { return it to m.range }
        }
        DAY_MONTH_YEAR.find(line)?.let { m ->
            val (d, monthName, y) = m.destructured
            monthOf(monthName)?.let { mo -> safeDate(y.toInt(), mo, d.toInt())?.let { return it to m.range } }
        }
        MONTH_DAY_YEAR.find(line)?.let { m ->
            val (monthName, d, y) = m.destructured
            monthOf(monthName)?.let { mo -> safeDate(y.toInt(), mo, d.toInt())?.let { return it to m.range } }
        }
        NUMERIC_DMY.find(line)?.let { m ->
            val (d, mo, y) = m.destructured
            safeDate(y.toInt(), mo.toInt(), d.toInt())?.let { return it to m.range }
        }
        return null
    }

    private fun monthOf(name: String): Int? = MONTHS[name.take(3).lowercase()]

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? = try {
        LocalDate.of(year, month, day)
    } catch (e: DateTimeException) {
        null
    }
}
