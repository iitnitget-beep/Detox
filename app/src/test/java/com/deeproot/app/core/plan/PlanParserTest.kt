package com.deeproot.app.core.plan

import com.deeproot.app.core.model.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class PlanParserTest {

    /** Unit tests run with the module directory (`app/`) as working directory. */
    private fun planFile(): File {
        val candidates = listOf(
            File("src/main/assets/lecture_plan.md"),
            File("app/src/main/assets/lecture_plan.md"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("lecture_plan.md not found; working dir = ${File(".").absolutePath}")
    }

    private val lectureLineRegex = Regex("^(Physics|Organic|Physical Chem|Inorganic|Maths): .+$")

    @Test
    fun `bundled plan parses every lecture and the count matches the file`() {
        val text = planFile().readText()
        val plan = PlanParser.parse(text)

        // Independent count straight from the file.
        val linesInFile = text.lines().count { lectureLineRegex.matches(it.trim()) }

        assertEquals(479, linesInFile)
        assertEquals(linesInFile, plan.lectures.size)
        assertEquals(linesInFile, plan.lectureLineCount)
        assertTrue(plan.isVerified)
        assertTrue("Unexpected warnings: ${plan.warnings}", plan.warnings.isEmpty())
    }

    @Test
    fun `bundled plan has the expected date range and NO LECTURE days`() {
        val plan = PlanParser.parse(planFile().readText())
        assertEquals(LocalDate.of(2026, 10, 15), plan.firstDate)
        assertEquals(LocalDate.of(2027, 1, 9), plan.lastDate)
        assertEquals(82, plan.lectureDates.size)
        assertEquals(
            listOf(
                LocalDate.of(2026, 10, 28),
                LocalDate.of(2026, 10, 31),
                LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 5),
                LocalDate.of(2026, 11, 17),
            ),
            plan.noLectureDates,
        )
    }

    @Test
    fun `bundled plan subject totals`() {
        val plan = PlanParser.parse(planFile().readText())
        val bySubject = plan.lectures.groupingBy { it.subject }.eachCount()
        assertEquals(170, bySubject[Subject.PHYSICS])
        assertEquals(79, bySubject[Subject.ORGANIC])
        assertEquals(48, bySubject[Subject.PHYSICAL_CHEM])
        assertEquals(56, bySubject[Subject.INORGANIC])
        assertEquals(126, bySubject[Subject.MATHS])
    }

    @Test
    fun `first day is parsed in order with deterministic ids`() {
        val plan = PlanParser.parse(planFile().readText())
        val first = plan.lectures.filter { it.date == LocalDate.of(2026, 10, 15) }
        assertEquals(6, first.size)
        assertEquals(PlannedLecture(LocalDate.of(2026, 10, 15), 1, Subject.PHYSICS, "Centre of Mass 1"), first[0])
        assertEquals("plan_2026-10-15_01", first[0].id)
        assertEquals(Subject.MATHS, first[5].subject)
        assertEquals("Permutation & Combination 1", first[5].title)
        assertEquals("plan_2026-10-15_06", first[5].id)
        assertEquals(plan.lectures.size, plan.lectures.map { it.id }.toSet().size)
    }

    @Test
    fun `accepts several heading styles, bullets and bold`() {
        val text = """
            # My plan — 15 Oct 2026 to 9 Jan 2027
            Some intro text that mentions NO LECTURE days.

            ### Thursday, 15 Oct 2026
            - **Physics:** Rotation 2
            * Maths: Matrix 1

            Oct 16, 2026
            1. Organic: GOC 3

            📅 17/10/2026 (Sat)
            Physical Chemistry: Thermodynamics 4
            Inorganic Chemistry: P Block 2
        """.trimIndent()
        val plan = PlanParser.parse(text)
        assertEquals(5, plan.lectures.size)
        assertTrue(plan.isVerified)
        assertEquals(LocalDate.of(2026, 10, 15), plan.lectures[0].date)
        assertEquals("Rotation 2", plan.lectures[0].title)
        assertEquals(Subject.PHYSICS, plan.lectures[0].subject)
        assertEquals(LocalDate.of(2026, 10, 16), plan.lectures[2].date)
        assertEquals(LocalDate.of(2026, 10, 17), plan.lectures[3].date)
        assertEquals(Subject.PHYSICAL_CHEM, plan.lectures[3].subject)
        assertEquals(Subject.INORGANIC, plan.lectures[4].subject)
        assertEquals(2, plan.lectures[4].order)
        assertTrue(plan.noLectureDates.isEmpty())
    }

    @Test
    fun `NO LECTURE on its own line or on the heading`() {
        val text = """
            ## 2026-10-27
            Physics: Rotation 14
            ## 2026-10-28 (Wed)
            NO LECTURE
            ## 29 Oct 2026 — No lecture
            ## 2026-10-30
            Maths: Relation & Function 8
        """.trimIndent()
        val plan = PlanParser.parse(text)
        assertEquals(2, plan.lectures.size)
        assertEquals(listOf(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 29)), plan.noLectureDates)
    }

    @Test
    fun `lectures before any heading are counted but not placed so verification fails`() {
        val text = """
            Physics: Orphan 1
            ## 2026-10-15
            Physics: Centre of Mass 1
        """.trimIndent()
        val plan = PlanParser.parse(text)
        assertEquals(1, plan.lectures.size)
        assertEquals(2, plan.lectureLineCount)
        assertFalse(plan.isVerified)
        assertEquals(1, plan.warnings.size)
    }

    @Test
    fun `unknown subjects are reported`() {
        val text = """
            ## 2026-10-15
            Biology: Cells 1
            Physics: Rotation 1
        """.trimIndent()
        val plan = PlanParser.parse(text)
        assertEquals(1, plan.lectures.size)
        assertTrue(plan.warnings.single().contains("unknown subject"))
    }

    @Test
    fun `repeated heading continues the order`() {
        val text = """
            ## 2026-10-15
            Physics: A 1
            ## 2026-10-16
            Physics: B 1
            ## 2026-10-15
            Maths: C 1
        """.trimIndent()
        val plan = PlanParser.parse(text)
        val day = plan.lectures.filter { it.date == LocalDate.of(2026, 10, 15) }
        assertEquals(listOf(1, 2), day.map { it.order })
    }

    @Test
    fun `empty text yields an unverified empty plan`() {
        val plan = PlanParser.parse("")
        assertTrue(plan.lectures.isEmpty())
        assertFalse(plan.isVerified)
    }
}
