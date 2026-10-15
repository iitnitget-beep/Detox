package com.deeproot.app.ui.today

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deeproot.app.core.model.Lecture
import com.deeproot.app.core.model.LectureSource
import com.deeproot.app.core.timer.TimeFormat
import com.deeproot.app.ui.components.SubjectChip
import com.deeproot.app.ui.theme.AppTheme

/**
 * One lecture. Tap toggles completion (like the prototype's label + checkbox); long-press starts multi-select;
 * in selection mode a tap toggles selection. The whole row is the touch target (≥ 64 dp tall).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LectureRow(
    lecture: Lecture,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStart: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val success = AppTheme.extended.success
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val stateText = when {
        selectionMode -> if (selected) "Selected" else "Not selected"
        lecture.completed -> "Completed"
        else -> "Not completed"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(
                role = if (selectionMode) null else Role.Checkbox,
                onClickLabel = if (selectionMode) "Toggle selection" else "Toggle completed",
                onLongClickLabel = "Select",
                onClick = onClick,
                // combinedClickable performs the long-press haptic itself.
                onLongClick = onLongClick,
            )
            .semantics {
                this.selected = selected
                stateDescription = stateText
            }
            .heightIn(min = 64.dp)
            .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(26.dp),
                )
            } else {
                // Visual only: the row handles the click so the touch target is the full row.
                Checkbox(
                    checked = lecture.completed,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(checkedColor = success, checkmarkColor = MaterialTheme.colorScheme.surface),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = lecture.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (lecture.completed) TextDecoration.LineThrough else null,
                color = if (lecture.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubjectChip(lecture.subject)
                if (lecture.source == LectureSource.CALENDAR) {
                    Icon(
                        Icons.Outlined.Event,
                        contentDescription = "From Google Calendar",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (lecture.completed) {
                    val studied = if (lecture.studiedSeconds >= 60) " · ${TimeFormat.shortDuration(lecture.studiedSeconds)}" else ""
                    Text(
                        text = "Completed$studied",
                        style = MaterialTheme.typography.labelMedium,
                        color = success,
                        maxLines = 1,
                    )
                } else if (lecture.studiedSeconds >= 60) {
                    Text(
                        text = "${TimeFormat.shortDuration(lecture.studiedSeconds)} studied",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        if (onStart != null && !selectionMode && !lecture.completed) {
            Spacer(Modifier.width(4.dp))
            FilledTonalButton(
                onClick = onStart,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Start")
            }
        }
    }
}
