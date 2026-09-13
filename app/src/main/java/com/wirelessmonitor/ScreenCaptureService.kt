package com.wirelessmonitor

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    companion object {

        const val EXTRA_RESULT_CODE =
            "result_code"

        const val EXTRA_DATA =
            "projection_data"

        const val EXTRA_RECEIVER_IP =
            "receiver_ip"

        private const val CHANNEL_ID =
            "wireless_monitor_capture"

        private const val PORT = 5000

        // The longest edge of the captured video is capped here
        // to keep bandwidth/encoder load sane over Wi-Fi. The
        // OnePlus 13's real aspect ratio is always preserved -
        // we no longer force a fixed 1280x720 (16:9) buffer,
        // which used to squash/stretch the image.
        private const val MAX_DIMENSION = 1920

        private const val FPS = 60

        private const val BITRATE =
            16_000_000

        private const val PACKET_CONFIG = 0
        private const val PACKET_FRAME = 1
    }

    private var mediaProjection:
            MediaProjection? = null

    private var virtualDisplay:
            VirtualDisplay? = null

    private var encoder:
            MediaCodec? = null

    private var inputSurface:
            Surface? = null

    private var socket:
            Socket? = null

    private var output:
            DataOutputStream? = null

    private var captureWidth = 0
    private var captureHeight = 0

    private val running =
        AtomicBoolean(false)

    override fun onCreate() {

        super.onCreate()

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent == null) {

            stopSelf()

            return START_NOT_STICKY
        }

        val resultCode =
            intent.getIntExtra(
                EXTRA_RESULT_CODE,
                Activity.RESULT_CANCELED
            )

        val projectionData =
            intent.getParcelableExtra<Intent>(
                EXTRA_DATA
            )

        val receiverIp =
            intent.getStringExtra(
                EXTRA_RECEIVER_IP
            )?.trim()

        if (
            resultCode != Activity.RESULT_OK ||
            projectionData == null ||
            receiverIp.isNullOrEmpty()
        ) {

            stopSelf()

            return START_NOT_STICKY
        }

        startForeground(
            1,
            createNotification()
        )

        Thread {

            startStreaming(
                resultCode,
                projectionData,
                receiverIp
            )

        }.start()

        return START_NOT_STICKY
    }

    private fun startStreaming(
        resultCode: Int,
        projectionData: Intent,
        receiverIp: String
    ) {

        try {

            val manager =
                getSystemService(
                    MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            mediaProjection =
                manager.getMediaProjection(
                    resultCode,
                    projectionData
                )

            socket =
                Socket(
                    receiverIp,
                    PORT
                )

            socket!!.tcpNoDelay = true

            output =
                DataOutputStream(
                    socket!!.getOutputStream()
                )

            setupEncoder()

            running.set(true)

            encodeLoop()

        } catch (_: Exception) {

            stopStreaming()
        }
    }

    /**
     * Reads the OnePlus 13's actual current screen resolution
     * (in its current, landscape-forced orientation) so the
     * captured video keeps the phone's real aspect ratio,
     * scaled down only if it exceeds MAX_DIMENSION.
     */
    private fun computeCaptureSize(): Pair<Int, Int> {

        var width: Int
        var height: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

            val windowManager =
                getSystemService(WINDOW_SERVICE) as WindowManager

            val bounds: Rect =
                windowManager.currentWindowMetrics.bounds

            width = bounds.width()
            height = bounds.height()

        } else {

            val displayManager =
                getSystemService(DISPLAY_SERVICE) as DisplayManager

            val display =
                displayManager.getDisplay(Display.DEFAULT_DISPLAY)

            val metrics = DisplayMetrics()

            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)

            width = metrics.widthPixels
            height = metrics.heightPixels
        }

        val longestEdge = maxOf(width, height)

        if (longestEdge > MAX_DIMENSION) {

            val scale = MAX_DIMENSION.toFloat() / longestEdge

            width = (width * scale).toInt()
            height = (height * scale).toInt()
        }

        // H.264 encoders require even dimensions.
        width -= width % 2
        height -= height % 2

        return Pair(width, height)
    }

    private fun setupEncoder() {

        val (width, height) = computeCaptureSize()

        captureWidth = width
        captureHeight = height

        val format =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                captureWidth,
                captureHeight
            )

        /*
         * IMPORTANT:
         *
         * COLOR_FormatSurface tells MediaCodec
         * that our input is the Surface created below.
         */
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            2130708361
        )

        format.setInteger(
            MediaFormat.KEY_BIT_RATE,
            BITRATE
        )

        format.setInteger(
            MediaFormat.KEY_FRAME_RATE,
            FPS
        )

        format.setInteger(
            MediaFormat.KEY_I_FRAME_INTERVAL,
            1
        )

        encoder =
            MediaCodec.createEncoderByType(
                MediaFormat.MIMETYPE_VIDEO_AVC
            )

        encoder!!.configure(
            format,
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        inputSurface =
            encoder!!.createInputSurface()

        encoder!!.start()

        virtualDisplay =
            mediaProjection!!.createVirtualDisplay(
                "WirelessMonitor",
                captureWidth,
                captureHeight,
                320,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            )
    }

    private fun encodeLoop() {

        val codec =
            encoder ?: return

        val info =
            MediaCodec.BufferInfo()

        var configurationSent = false

        while (running.get()) {

            val index =
                codec.dequeueOutputBuffer(
                    info,
                    10_000
                )

            if (
                index ==
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            ) {

                val format =
                    codec.outputFormat

                val sps =
                    format.getByteBuffer(
                        "csd-0"
                    )

                val pps =
                    format.getByteBuffer(
                        "csd-1"
                    )

                if (
                    sps != null &&
                    pps != null &&
                    !configurationSent
                ) {

                    sendConfiguration(
                        sps,
                        pps
                    )

                    configurationSent = true
                }

                continue
            }

            if (index < 0) {
                continue
            }

            val buffer =
                codec.getOutputBuffer(index)

            if (
                buffer != null &&
                info.size > 0 &&
                configurationSent
            ) {

                buffer.position(info.offset)

                buffer.limit(
                    info.offset + info.size
                )

                val data =
                    ByteArray(info.size)

                buffer.get(data)

                sendFrame(
                    data,
                    info.flags,
                    info.presentationTimeUs
                )
            }

            codec.releaseOutputBuffer(
                index,
                false
            )
        }
    }

    private fun sendConfiguration(
        spsBuffer: ByteBuffer,
        ppsBuffer: ByteBuffer
    ) {

        val sps =
            ByteArray(
                spsBuffer.remaining()
            )

        spsBuffer.get(sps)

        val pps =
            ByteArray(
                ppsBuffer.remaining()
            )

        ppsBuffer.get(pps)

        synchronized(this) {

            output?.writeInt(
                PACKET_CONFIG
            )

            // The receiver needs the real capture resolution
            // to configure its decoder and to crop-fill the
            // Pad's screen correctly.
            output?.writeInt(
                captureWidth
            )

            output?.writeInt(
                captureHeight
            )

            val totalSize =
                4 +
                        sps.size +
                        4 +
                        pps.size

            output?.writeInt(
                totalSize
            )

            output?.writeInt(
                sps.size
            )

            output?.write(
                sps
            )

            output?.writeInt(
                pps.size
            )

            output?.write(
                pps
            )

            output?.flush()
        }
    }

    private fun sendFrame(
        data: ByteArray,
        flags: Int,
        presentationTimeUs: Long
    ) {

        synchronized(this) {

            output?.writeInt(
                PACKET_FRAME
            )

            output?.writeInt(
                data.size
            )

            output?.writeInt(
                flags
            )

            output?.writeLong(
                presentationTimeUs
            )

            output?.write(
                data
            )

            output?.flush()
        }
    }

    private fun stopStreaming() {

        running.set(false)

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }

        try {
            encoder?.stop()
        } catch (_: Exception) {
        }

        try {
            encoder?.release()
        } catch (_: Exception) {
        }

        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }

        try {
            output?.close()
        } catch (_: Exception) {
        }

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        virtualDisplay = null
        mediaProjection = null
        encoder = null
        inputSurface = null
        output = null
        socket = null

        stopSelf()
    }

    override fun onDestroy() {

        stopStreaming()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }

    private fun createNotificationChannel() {

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Wireless Monitor",
                NotificationManager.IMPORTANCE_LOW
            )

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.createNotificationChannel(
            channel
        )
    }

    private fun createNotification():
            Notification {

        return Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                "Wireless Monitor"
            )
            .setContentText(
                "Screen streaming is active"
            )
            .setSmallIcon(
                android.R.drawable.ic_menu_view
            )
            .build()
    }
}