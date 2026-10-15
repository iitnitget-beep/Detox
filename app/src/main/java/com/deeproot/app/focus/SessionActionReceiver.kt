package com.deeproot.app.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Handles the Pause / Resume / Finish buttons of the session notification. */
class SessionActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun controller(): StudySessionController
    }

    override fun onReceive(context: Context, intent: Intent) {
        val controller = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).controller()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_PAUSE -> controller.pause()
                    ACTION_RESUME -> controller.resume()
                    ACTION_FINISH -> controller.finish(markCompleted = true)
                }
            } catch (e: Exception) {
                Log.e("SessionActionReceiver", "Action ${intent.action} failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.deeproot.app.action.SESSION_PAUSE"
        const val ACTION_RESUME = "com.deeproot.app.action.SESSION_RESUME"
        const val ACTION_FINISH = "com.deeproot.app.action.SESSION_FINISH"
    }
}
