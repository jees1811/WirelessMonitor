package com.wirelessmonitor

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView

class VideoReceiverView(
    context: Context
) : TextureView(context),
    TextureView.SurfaceTextureListener {

    private var receiver: VideoReceiver? = null

    init {
        surfaceTextureListener = this
    }

    fun start() {
        // Receiver will start when the Surface is ready.
    }

    fun stop() {
        receiver?.stop()
        receiver = null
    }

    override fun onSurfaceTextureAvailable(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int
    ) {
        val surface = Surface(surfaceTexture)

        receiver = VideoReceiver(
            surface = surface
        )

        receiver?.start()
    }

    override fun onSurfaceTextureSizeChanged(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int
    ) {
        // The video renderer automatically scales to the
        // Pad 2 display surface.
    }

    override fun onSurfaceTextureDestroyed(
        surfaceTexture: SurfaceTexture
    ): Boolean {
        receiver?.stop()
        receiver = null
        return true
    }

    override fun onSurfaceTextureUpdated(
        surfaceTexture: SurfaceTexture
    ) {
        // Nothing required.
    }
}
