package com.deeproot.app.core.calendar

import com.deeproot.app.core.model.Subject

/**
 * Guesses a [Subject] from a calendar event title.
 *
 * Priority:
 * 1. Explicit subject words ("Inorganic", "Organic", "Physical Chem", "Physics", "Maths", ...)
 * 2. Chapter keywords taken from the lecture plan ("Rotation" → Physics, "GOC" → Organic, "Matrix" → Maths, ...)
 * 3. A generic "Chem"/"Chemistry" → Physical Chem
 * 4. Otherwise [Subject.OTHER]
 *
 * Matching is case-insensitive and whole-word ("inorganic" never matches the "organic" rule).
 * The same table is ported to `functions/src/calendar/subject.ts`.
 */
object SubjectGuesser {

    private class Rule(val subject: Subject, keywords: List<String>) {
        val patterns: List<Regex> = keywords.map { keyword ->
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(keyword) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        }

        fun matches(text: String): Boolean = patterns.any { it.containsMatchIn(text) }
    }

    private val explicitRules = listOf(
        Rule(Subject.INORGANIC, listOf("inorganic", "inorg", "inorganic chemistry")),
        Rule(Subject.ORGANIC, listOf("organic", "orgo", "organic chemistry")),
        Rule(Subject.PHYSICAL_CHEM, listOf("physical chem", "physical chemistry", "pchem", "p chem", "phy chem")),
        Rule(Subject.PHYSICS, listOf("physics", "phy", "phys")),
        Rule(Subject.MATHS, listOf("maths", "math", "mathematics")),
    )

    private val chapterRules = listOf(
        Rule(
            Subject.PHYSICAL_CHEM,
            listOf(
                "chemical kinetics", "kinetics", "thermodynamics", "chemical equilibrium", "ionic equilibrium",
                "equilibrium", "electrochemistry", "electrochem", "structure of atom", "atomic structure",
                "mole concept", "stoichiometry", "solutions", "solid state", "surface chemistry", "redox",
            ),
        ),
        Rule(
            Subject.INORGANIC,
            listOf(
                "chemical bonding", "bonding", "hydrogen", "s block", "s-block", "p block", "p-block", "d block",
                "d-block", "f block", "d and f block", "coordination compound", "coordination compounds",
                "coordination", "metallurgy", "salt analysis", "periodic table", "periodicity",
                "qualitative analysis",
            ),
        ),
        Rule(
            Subject.ORGANIC,
            listOf(
                "iupac", "goc", "general organic", "isomerism", "hydrocarbon", "hydrocarbons", "alkane", "alkene",
                "alkyne", "haloalkane", "haloalkanes", "haloarene", "haloarenes", "alcohol", "alcohols", "phenol",
                "ether", "amine", "amines", "aldehyde", "aldehydes", "ketone", "ketones", "carboxylic",
                "biomolecule", "biomolecules", "polymer", "polymers", "environmental chemistry",
                "chemistry in everyday life", "chemistry in life", "aromatic",
            ),
        ),
        Rule(
            Subject.PHYSICS,
            listOf(
                "centre of mass", "center of mass", "rotation", "rotational", "oscillation", "oscillations", "shm",
                "ktg", "kinetic theory", "thermal", "fluid", "fluids", "wave", "waves", "electric charges",
                "electrostatics", "electric field", "modern physics", "current electricity", "capacitor",
                "capacitors", "capacitance", "wave optics", "ray optics", "optics", "gravitation", "magnetism",
                "magnetic", "em wave", "em waves", "semiconductor", "semiconductors", "emi",
                "electromagnetic induction", "error", "errors", "units and dimensions", "kinematics", "nlm",
                "laws of motion", "work power energy", "wpe", "friction", "circular motion", "ac",
                "alternating current", "elasticity", "properties of solids",
            ),
        ),
        Rule(
            Subject.MATHS,
            listOf(
                "binomial", "binomial theorem", "permutation", "permutations", "combination", "combinations", "p&c",
                "pnc", "determinant", "determinants", "matrix", "matrices", "sets", "set theory", "relation",
                "relations", "function", "functions", "inverse trig", "itf", "trigonometry", "trigonometric",
                "lcd", "limits", "continuity", "differentiability", "derivative", "derivatives", "differentiation",
                "aod", "integration", "integral", "integrals", "differential equation", "differential equations",
                "straight line", "straight lines", "statistics", "circle", "circles", "solution of triangle",
                "triangle", "parabola", "ellipse", "hyperbola", "conic", "conics", "vector", "vectors", "3d",
                "three dimensional", "probability", "complex number", "complex numbers", "sequence", "series",
                "quadratic", "logarithm",
            ),
        ),
    )

    private val genericChemistry = Rule(Subject.PHYSICAL_CHEM, listOf("chem", "chemistry"))

    fun guess(title: String?): Subject {
        val text = title?.trim().orEmpty()
        if (text.isEmpty()) return Subject.OTHER
        explicitRules.firstOrNull { it.matches(text) }?.let { return it.subject }
        chapterRules.firstOrNull { it.matches(text) }?.let { return it.subject }
        if (genericChemistry.matches(text)) return genericChemistry.subject
        return Subject.OTHER
    }
}
