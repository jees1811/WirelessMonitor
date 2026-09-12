package com.wirelessmonitor

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout

class ReceiverActivity : Activity() {

    private lateinit var videoView: VideoReceiverView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        hideSystemBars()

        videoView = VideoReceiverView(this)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(videoView)

        setContentView(root)

        videoView.start()
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
        videoView.stop()
        super.onDestroy()
    }
}
