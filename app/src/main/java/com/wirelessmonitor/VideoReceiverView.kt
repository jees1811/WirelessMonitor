package com.wirelessmonitor

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView

class VideoReceiverView(
    context: Context
) : TextureView(context),
    TextureView.SurfaceTextureListener {

    private var receiver: VideoReceiver? = null

    private var videoWidth = 0
    private var videoHeight = 0

    var onStatus: ((String) -> Unit)? = null

    init {
        surfaceTextureListener = this
    }

    fun start() {}

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
            surface = surface,
            onVideoSize = { w, h ->
                videoWidth = w
                videoHeight = h
                applyCropFillTransform()
            },
            onStatus = { status ->
                onStatus?.invoke(status)
            }
        )

        receiver?.start()
    }

    override fun onSurfaceTextureSizeChanged(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int
    ) {
        applyCropFillTransform()
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
    ) {}

    private fun applyCropFillTransform() {

        if (videoWidth == 0 || videoHeight == 0) return

        val viewWidth = width
        val viewHeight = height

        if (viewWidth == 0 || viewHeight == 0) return

        val viewAspect = viewWidth.toFloat() / viewHeight
        val videoAspect = videoWidth.toFloat() / videoHeight

        var scaleX = 1f
        var scaleY = 1f

        if (videoAspect > viewAspect) {
            scaleX = videoAspect / viewAspect
        } else {
            scaleY = viewAspect / videoAspect
        }

        val matrix = Matrix()

        matrix.setScale(
            scaleX,
            scaleY,
            viewWidth / 2f,
            viewHeight / 2f
        )

        setTransform(matrix)
    }
}