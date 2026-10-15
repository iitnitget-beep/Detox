package com.deeproot.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.deeproot.app.core.stats.DayTotal
import com.deeproot.app.core.time.Dates
import com.deeproot.app.core.timer.TimeFormat
import java.time.LocalDate

/** Seven bars (oldest → today) of study time, labelled with the weekday and the duration. */
@Composable
fun WeeklyBarChart(days: List<DayTotal>, today: LocalDate, modifier: Modifier = Modifier) {
    val maxSeconds = (days.maxOfOrNull { it.studiedSeconds } ?: 0L).coerceAtLeast(60L)
    val barColor = MaterialTheme.colorScheme.primary
    val todayColor = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val description = days.joinToString { "${Dates.weekdayShort(it.date)} ${TimeFormat.shortDuration(it.studiedSeconds)}" }

    Column(modifier.semantics { contentDescription = "Study time this week: $description" }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            days.forEach { day ->
                Text(
                    text = if (day.studiedSeconds >= 60) TimeFormat.shortDuration(day.studiedSeconds) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(vertical = 4.dp),
        ) {
            val slot = size.width / days.size.coerceAtLeast(1)
            val barWidth = slot * 0.55f
            val radius = CornerRadius(barWidth / 4f, barWidth / 4f)
            days.forEachIndexed { index, day ->
                val left = slot * index + (slot - barWidth) / 2f
                drawRoundRect(color = track, topLeft = Offset(left, 0f), size = Size(barWidth, size.height), cornerRadius = radius)
                val fraction = (day.studiedSeconds.toFloat() / maxSeconds).coerceIn(0f, 1f)
                if (fraction > 0f) {
                    val height = (size.height * fraction).coerceAtLeast(barWidth / 2f)
                    drawRoundRect(
                        color = if (day.date == today) todayColor else barColor,
                        topLeft = Offset(left, size.height - height),
                        size = Size(barWidth, height),
                        cornerRadius = radius,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            days.forEach { day ->
                Text(
                    text = if (day.date == today) "Today" else Dates.weekdayShort(day.date),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (day.date == today) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
        }
    }
}
