package com.wirelessmonitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.view.Surface
import java.io.DataOutputStream
import java.net.Socket
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

        private const val WIDTH = 1280
        private const val HEIGHT = 720

        private const val FPS = 60

        private const val BITRATE =
            12_000_000
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

    private fun setupEncoder() {

        val format =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                WIDTH,
                HEIGHT
            )

        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodec.CONFIGURE_FLAG_ENCODE
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
                WIDTH,
                HEIGHT,
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

        while (running.get()) {

            val index =
                codec.dequeueOutputBuffer(
                    info,
                    10_000
                )

            if (index >= 0) {

                val buffer =
                    codec.getOutputBuffer(index)

                if (
                    buffer != null &&
                    info.size > 0
                ) {

                    buffer.position(info.offset)
                    buffer.limit(
                        info.offset + info.size
                    )

                    val data =
                        ByteArray(info.size)

                    buffer.get(data)

                    synchronized(this) {

                        output?.writeInt(
                            data.size
                        )

                        output?.writeInt(
                            info.flags
                        )

                        output?.write(
                            data
                        )

                        output?.flush()
                    }
                }

                codec.releaseOutputBuffer(
                    index,
                    false
                )

            } else if (
                index ==
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            ) {
                // Encoder format becomes available here.
                // The decoder can receive H.264 stream data.
            }
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
