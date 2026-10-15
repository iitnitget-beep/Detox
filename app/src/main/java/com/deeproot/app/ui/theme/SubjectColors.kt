package com.deeproot.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.deeproot.app.core.model.Subject

/** Accent color per subject (used for chips, chart bars and progress bars). */
@Composable
fun subjectColor(subject: String): Color {
    val dark = AppTheme.extended.isDark
    return when (Subject.fromLabel(subject)) {
        Subject.PHYSICS -> if (dark) Color(0xFF60A5FA) else Color(0xFF2563EB)
        Subject.ORGANIC -> if (dark) Color(0xFF34D399) else Color(0xFF059669)
        Subject.PHYSICAL_CHEM -> if (dark) Color(0xFFFBBF24) else Color(0xFFB45309)
        Subject.INORGANIC -> if (dark) Color(0xFFA78BFA) else Color(0xFF7C3AED)
        Subject.MATHS -> if (dark) Color(0xFFF472B6) else Color(0xFFDB2777)
        Subject.OTHER -> if (dark) Color(0xFF9AA1B2) else Color(0xFF6B7280)
    }
}
