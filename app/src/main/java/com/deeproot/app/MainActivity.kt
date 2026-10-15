package com.deeproot.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deeproot.app.notifications.AppIntents
import com.deeproot.app.ui.main.MainViewModel
import com.deeproot.app.ui.navigation.AppNavigation
import com.deeproot.app.ui.theme.LectureTrackerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** Screen requested by a notification (Focus or Today). */
    private var pendingDestination by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingDestination = intent?.getStringExtra(AppIntents.EXTRA_DESTINATION)
        setContent {
            LectureTrackerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val start by viewModel.startDestination.collectAsStateWithLifecycle()
                    start?.let { startDestination ->
                        AppNavigation(
                            startDestination = startDestination,
                            pendingDestination = pendingDestination,
                            onDestinationHandled = { pendingDestination = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(AppIntents.EXTRA_DESTINATION)?.let { pendingDestination = it }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }
}
