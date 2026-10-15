package com.deeproot.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.deeproot.app.core.model.Lecture
import com.deeproot.app.core.timer.TimeFormat
import com.deeproot.app.ui.components.SubjectChip

private val QUICK_CHOICES = listOf(90 to "1.5h", 120 to "2h", 180 to "3h", 240 to "4h")
private const val MAX_MINUTES = 12 * 60
private const val MINUTE_STEP = 5

/** "How long will this lecture take?" — hours + minutes picker (default 2 h) with quick chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartLectureDialog(
    lecture: Lecture,
    focusModeEnabled: Boolean,
    focusReady: Boolean,
    onDismiss: () -> Unit,
    onStart: (plannedMinutes: Int) -> Unit,
) {
    val initial = lecture.plannedMinutes.takeIf { it in 1..MAX_MINUTES } ?: Lecture.DEFAULT_PLANNED_MINUTES
    var totalMinutes by rememberSaveable(lecture.id) { mutableIntStateOf(initial) }
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How long will this lecture take?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SubjectChip(lecture.subject)
                    Spacer(Modifier.width(8.dp))
                    Text(lecture.title, style = MaterialTheme.typography.bodyLarge)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Stepper(
                        value = hours,
                        unit = "h",
                        label = "hours",
                        onDecrement = { totalMinutes = (totalMinutes - 60).coerceAtLeast(MINUTE_STEP) },
                        onIncrement = { totalMinutes = (totalMinutes + 60).coerceAtMost(MAX_MINUTES) },
                    )
                    Stepper(
                        value = minutes,
                        unit = "m",
                        label = "minutes",
                        onDecrement = { totalMinutes = (totalMinutes - MINUTE_STEP).coerceAtLeast(MINUTE_STEP) },
                        onIncrement = { totalMinutes = (totalMinutes + MINUTE_STEP).coerceAtMost(MAX_MINUTES) },
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QUICK_CHOICES.forEach { (value, label) ->
                        FilterChip(
                            selected = totalMinutes == value,
                            onClick = { totalMinutes = value },
                            label = { Text(label) },
                        )
                    }
                }
                val focusText = when {
                    !focusModeEnabled -> "Focus mode is off (Settings)."
                    !focusReady -> "Focus mode needs Usage access and Display over other apps — the timer still works."
                    else -> "Focus mode will cover other apps until ${TimeFormat.minutesLabel(totalMinutes)} are done. Allowed apps, phone and Settings always work."
                }
                Text(focusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = { onStart(totalMinutes) }) { Text("Start ${TimeFormat.minutesLabel(totalMinutes)}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Stepper(value: Int, unit: String, label: String, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onIncrement, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "More $label")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "%02d%s".format(value, unit),
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .semantics { contentDescription = "$value $label" },
        )
        Spacer(Modifier.height(4.dp))
        FilledTonalIconButton(onClick = onDecrement, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = "Fewer $label")
        }
    }
}
