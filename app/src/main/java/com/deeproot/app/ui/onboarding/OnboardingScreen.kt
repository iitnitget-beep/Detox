package com.deeproot.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deeproot.app.permissions.AppPermission
import com.deeproot.app.ui.permissions.copyFor
import com.deeproot.app.ui.permissions.rememberPermissionRequester
import com.deeproot.app.ui.theme.AppTheme

private enum class Step(val permission: AppPermission?) {
    WELCOME(null),
    NOTIFICATIONS(AppPermission.NOTIFICATIONS),
    USAGE(AppPermission.USAGE_ACCESS),
    OVERLAY(AppPermission.OVERLAY),
    BATTERY(AppPermission.BATTERY),
    DONE(null),
}

/**
 * First launch: one explainer per permission. Nothing is requested until the user taps Allow, and each step can be
 * skipped ("Not now"); the matching feature simply stays off until the permission is granted later.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val permissions by viewModel.checker.state.collectAsStateWithLifecycle()
    val finishing by viewModel.finishing.collectAsStateWithLifecycle()
    val defaultAllowed by viewModel.defaultAllowedLabels.collectAsStateWithLifecycle()
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var awaitingIndex by rememberSaveable { mutableIntStateOf(-1) }
    val step = Step.entries[stepIndex]

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.checker.refresh() }
    val request = rememberPermissionRequester(viewModel.checker)

    // Coming back from the system screen with the permission granted moves on automatically.
    LaunchedEffect(permissions, awaitingIndex) {
        val awaited = Step.entries.getOrNull(awaitingIndex)?.permission ?: return@LaunchedEffect
        if (permissions.isGranted(awaited) && stepIndex == awaitingIndex) {
            awaitingIndex = -1
            stepIndex = (stepIndex + 1).coerceAtMost(Step.entries.lastIndex)
        }
    }

    BackHandler(enabled = stepIndex > 0) { stepIndex -= 1 }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            ProgressDots(current = stepIndex, total = Step.entries.size)
            AnimatedContent(targetState = step, label = "onboarding-step", modifier = Modifier.weight(1f)) { current ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (current) {
                        Step.WELCOME -> StepText(
                            emoji = "📚",
                            title = "Welcome to Lecture Tracker",
                            body = "Your plan of 479 lectures (15 Oct 2026 – 9 Jan 2027) is ready on this phone.\n\n" +
                                "The next steps explain each permission before anything is asked. Allow only what you want — " +
                                "you can change it later.",
                        )
                        Step.DONE -> StepText(
                            emoji = "🎯",
                            title = "You're all set",
                            body = buildString {
                                append("During a study session Focus mode allows this app, the phone, emergency calls and Settings")
                                if (defaultAllowed.isNotEmpty()) append(", plus ${defaultAllowed.joinToString()}")
                                append(". Add more allowed apps any time in Settings → Allowed apps.")
                            },
                        )
                        else -> current.permission?.let { stepPermission ->
                            val copy = copyFor(stepPermission)
                            val stepGranted = permissions.isGranted(stepPermission)
                            StepText(emoji = copy.emoji, title = copy.title, body = copy.why)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (stepGranted) "Allowed ✓" else copy.how,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (stepGranted) AppTheme.extended.success else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            val permission = step.permission
            val granted = permission != null && permissions.isGranted(permission)
            Button(
                onClick = {
                    when {
                        step == Step.DONE -> viewModel.finish(onFinished)
                        permission == null || granted -> stepIndex += 1
                        else -> {
                            awaitingIndex = stepIndex
                            request(permission)
                        }
                    }
                },
                enabled = !finishing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(
                    when {
                        step == Step.WELCOME -> "Get started"
                        step == Step.DONE -> "Start studying"
                        granted -> "Continue"
                        else -> "Allow"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (permission != null && !granted) {
                TextButton(
                    onClick = { stepIndex += 1 },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Not now") }
            } else {
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun StepText(emoji: String, title: String, body: String) {
    Text(emoji, style = MaterialTheme.typography.displayMedium)
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

@Composable
private fun ProgressDots(current: Int, total: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics { contentDescription = "Step ${current + 1} of $total" },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        repeat(total) { index ->
            Box(
                Modifier
                    .size(if (index == current) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (index <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
        }
    }
}
