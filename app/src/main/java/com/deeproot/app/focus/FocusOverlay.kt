package com.deeproot.app.focus

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.deeproot.app.R
import com.deeproot.app.notifications.AppIntents

/**
 * The full-screen "Focus mode: finish your lecture" cover, drawn with SYSTEM_ALERT_WINDOW
 * (TYPE_APPLICATION_OVERLAY). It sits above apps but below the status/navigation bars, so Home, Recents, the
 * notification shade and emergency calls keep working. Must be used from the main thread.
 */
class FocusOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    val isShowing: Boolean get() = view != null

    @SuppressLint("InflateParams")
    fun show(lectureName: String, remainingText: String) {
        if (!Settings.canDrawOverlays(context)) return
        val existing = view
        if (existing != null) {
            update(existing, lectureName, remainingText)
            return
        }
        val root = LayoutInflater.from(context).inflate(R.layout.focus_block_overlay, null)
        root.findViewById<Button>(R.id.focus_overlay_back).setOnClickListener { backToStudy() }
        root.findViewById<Button>(R.id.focus_overlay_phone).setOnClickListener { openDialer() }
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                backToStudy()
                true
            } else {
                false
            }
        }
        update(root, lectureName, remainingText)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        )
        try {
            windowManager.addView(root, params)
            root.requestFocus()
            view = root
        } catch (e: RuntimeException) {
            // BadTokenException / permission revoked between the check and addView.
            Log.w(TAG, "Could not show the focus overlay", e)
        }
    }

    fun updateText(lectureName: String, remainingText: String) {
        view?.let { update(it, lectureName, remainingText) }
    }

    fun hide() {
        val current = view ?: return
        view = null
        try {
            windowManager.removeView(current)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not remove the focus overlay", e)
        }
    }

    private fun update(root: View, lectureName: String, remainingText: String) {
        root.findViewById<TextView>(R.id.focus_overlay_lecture).text = lectureName
        root.findViewById<TextView>(R.id.focus_overlay_remaining).text = remainingText
    }

    private fun backToStudy() {
        hide()
        context.startActivity(AppIntents.open(context, AppIntents.DESTINATION_FOCUS))
    }

    private fun openDialer() {
        hide()
        val intent = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No dialer available", e)
        }
    }

    private companion object {
        const val TAG = "FocusOverlay"
    }
}
