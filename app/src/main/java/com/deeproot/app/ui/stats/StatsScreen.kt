package com.deeproot.app.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deeproot.app.core.stats.StatsSnapshot
import com.deeproot.app.core.stats.SubjectTotal
import com.deeproot.app.core.timer.TimeFormat
import com.deeproot.app.ui.components.AppCard
import com.deeproot.app.ui.components.EmptyView
import com.deeproot.app.ui.components.LoadingView
import com.deeproot.app.ui.components.SectionTitle
import com.deeproot.app.ui.components.SubjectChip
import com.deeproot.app.ui.theme.AppTheme
import com.deeproot.app.ui.theme.subjectColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Stats") }) },
    ) { inner ->
        val stats = state.stats
        when {
            state.loading -> LoadingView("Crunching numbers…", Modifier.padding(inner))
            stats == null || stats.isEmpty -> EmptyView(
                emoji = "📈",
                title = "No data yet",
                message = "Your lectures are still loading.",
                modifier = Modifier.padding(inner),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = inner.calculateTopPadding() + 4.dp,
                    bottom = inner.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { HeadlineCards(stats) }
                item {
                    AppCard {
                        Column(Modifier.padding(16.dp)) {
                            Text("This week", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            if (stats.week.all { it.studiedSeconds < 60 }) {
                                Text(
                                    "No study sessions in the last 7 days. Tap Start on a lecture to time it.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            WeeklyBarChart(stats.week, state.today)
                        }
                    }
                }
                item { SectionTitle("By subject") }
                items(stats.subjects, key = { it.subject }) { subject -> SubjectRow(subject) }
                state.cloud?.let { cloud ->
                    item {
                        Text(
                            "☁️ Cloud stats (onLectureCompleted): ${cloud.completedLectures} of ${cloud.totalLectures} completed · " +
                                "${TimeFormat.shortDuration(cloud.studiedSeconds)} studied",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                item {
                    Text(
                        "Total studied: ${TimeFormat.shortDuration(stats.totalStudiedSeconds)} · " +
                            "${stats.totalCompleted} of ${stats.totalLectures} lectures completed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeadlineCards(stats: StatsSnapshot) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(
            value = TimeFormat.shortDuration(stats.todayStudiedSeconds),
            label = "studied today",
            sub = "${stats.todayCompleted} completed today",
            modifier = Modifier.weight(1f),
        )
        StatCard(
            value = "🔥 ${stats.streakDays}",
            label = "day streak",
            sub = "days in a row with a completed lecture",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCard(value: String, label: String, sub: String, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SubjectRow(subject: SubjectTotal) {
    AppCard {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SubjectChip(subject.subject)
                Spacer(Modifier.weight(1f))
                Text(
                    "${subject.completed}/${subject.total}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (subject.completed == subject.total && subject.total > 0) AppTheme.extended.success else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { subject.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50)),
                color = subjectColor(subject.subject),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${TimeFormat.shortDuration(subject.studiedSeconds)} studied · ${(subject.fraction * 100).toInt()}% complete",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
