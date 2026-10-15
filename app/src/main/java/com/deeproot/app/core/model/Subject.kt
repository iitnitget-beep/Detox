package com.deeproot.app.core.model

/**
 * Subjects used by the lecture plan. Labels are the exact strings stored in Room and Firestore.
 * [OTHER] is only used for calendar events whose subject cannot be guessed.
 */
enum class Subject(val label: String) {
    PHYSICS("Physics"),
    ORGANIC("Organic"),
    PHYSICAL_CHEM("Physical Chem"),
    INORGANIC("Inorganic"),
    MATHS("Maths"),
    OTHER("Other");

    companion object {
        /** The five subjects that appear in the lecture plan, in display order. */
        val planSubjects: List<Subject> = listOf(PHYSICS, ORGANIC, PHYSICAL_CHEM, INORGANIC, MATHS)

        /** Every label accepted by the Firestore security rules. */
        val allLabels: List<String> = entries.map { it.label }

        private val WHITESPACE = Regex("\\s+")

        private val aliases: Map<String, Subject> = mapOf(
            "physics" to PHYSICS,
            "organic" to ORGANIC,
            "organic chem" to ORGANIC,
            "organic chemistry" to ORGANIC,
            "physical chem" to PHYSICAL_CHEM,
            "physical chemistry" to PHYSICAL_CHEM,
            "inorganic" to INORGANIC,
            "inorganic chem" to INORGANIC,
            "inorganic chemistry" to INORGANIC,
            "maths" to MATHS,
            "math" to MATHS,
            "mathematics" to MATHS,
            "other" to OTHER,
        )

        /** Case-insensitive match on a label or a known alias; `null` when unknown. */
        fun parseOrNull(text: String): Subject? =
            aliases[text.trim().lowercase().replace(WHITESPACE, " ")]

        /** Label → [Subject], falling back to [OTHER]. */
        fun fromLabel(label: String): Subject = parseOrNull(label) ?: OTHER
    }
}
