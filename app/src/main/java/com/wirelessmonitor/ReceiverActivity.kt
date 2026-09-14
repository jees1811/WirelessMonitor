package com.wirelessmonitor

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class ReceiverActivity : Activity() {

    private lateinit var videoView: VideoReceiverView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoView = VideoReceiverView(this)

        statusText = TextView(this).apply {
            text = "Starting..."
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        root.addView(
            videoView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            statusText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        )

        setContentView(root)

        hideSystemBars()

        videoView.onStatus = { status ->
            runOnUiThread {
                statusText.text = status
                statusText.visibility =
                    if (status == "Connected") View.GONE else View.VISIBLE
            }
        }

        videoView.start()

        NsdHelper.registerService(this, VideoReceiver.PORT)
    }

    private fun hideSystemBars() {
        window.insetsController?.let { controller ->
            controller.hide(
                WindowInsets.Type.statusBars() or
                        WindowInsets.Type.navigationBars()
            )
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun onDestroy() {
        NsdHelper.unregisterService(this)
        videoView.stop()
        super.onDestroy()
    }
}