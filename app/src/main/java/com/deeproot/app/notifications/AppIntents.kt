package com.deeproot.app.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.deeproot.app.MainActivity

/** Intents that open a specific screen of the app (used by notifications). */
object AppIntents {
    const val EXTRA_DESTINATION = "com.deeproot.app.extra.DESTINATION"
    const val DESTINATION_TODAY = "today"
    const val DESTINATION_FOCUS = "focus"

    fun open(context: Context, destination: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_DESTINATION, destination)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun openPending(context: Context, destination: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            open(context, destination),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
