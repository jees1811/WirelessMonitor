package com.wirelessmonitor

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    companion object {

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val EXTRA_RECEIVER_IP = "receiver_ip"
        const val EXTRA_MAX_DIMENSION = "max_dimension"
        const val EXTRA_VIDEO_BITRATE = "video_bitrate"

        const val ACTION_STOP = "com.wirelessmonitor.STOP"

        private const val CHANNEL_ID = "wireless_monitor_capture"

        const val VIDEO_PORT = 5000
        const val AUDIO_PORT = 5001

        private const val DEFAULT_MAX_DIMENSION = 1920
        private const val DEFAULT_VIDEO_BITRATE = 16_000_000

        private const val FPS = 60

        private const val AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_BITRATE = 128_000

        private const val TYPE_VIDEO_CONFIG = 10
        private const val TYPE_VIDEO_FRAME = 11
        private const val TYPE_AUDIO_CONFIG = 20
        private const val TYPE_AUDIO_FRAME = 21

        private const val HEADER_SIZE = 32
        private const val FRAGMENT_PAYLOAD_SIZE = 1400
        private const val AUDIO_CONFIG_RESEND_INTERVAL_MS = 1000L
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var videoEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null

    private var videoSocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var receiverAddress: InetAddress? = null

    private var videoFrameCounter = 0
    private var audioFrameCounter = 0

    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null

    private var cachedAudioSampleRate = 0
    private var cachedAudioChannelCount = 0
    private var cachedAudioCsd0: ByteArray? = null
    private var lastAudioConfigResendTime = 0L

    private var captureWidth = 0
    private var captureHeight = 0

    private var maxDimension = DEFAULT_MAX_DIMENSION
    private var videoBitrate = DEFAULT_VIDEO_BITRATE

    private val running = AtomicBoolean(false)

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent?.action == ACTION_STOP) {
            stopStreaming()
            return START_NOT_STICKY
        }

        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode =
            intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)

        val projectionData =
            intent.getParcelableExtra<Intent>(EXTRA_DATA)

        val receiverIp =
            intent.getStringExtra(EXTRA_RECEIVER_IP)?.trim()

        maxDimension =
            intent.getIntExtra(EXTRA_MAX_DIMENSION, DEFAULT_MAX_DIMENSION)

        videoBitrate =
            intent.getIntExtra(EXTRA_VIDEO_BITRATE, DEFAULT_VIDEO_BITRATE)

        if (
            resultCode != Activity.RESULT_OK ||
            projectionData == null ||
            receiverIp.isNullOrEmpty()
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(1, createNotification())

        acquireWakeLock()

        Thread {
            startStreaming(resultCode, projectionData, receiverIp)
        }.start()

        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {

        try {

            val powerManager = getSystemService(POWER_SERVICE) as PowerManager

            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "WirelessMonitor::CastWakeLock"
            )

            wakeLock?.acquire(12 * 60 * 60 * 1000L)

        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun startStreaming(
        resultCode: Int,
        projectionData: Intent,
        receiverIp: String
    ) {

        try {

            val manager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            mediaProjection =
                manager.getMediaProjection(resultCode, projectionData)

            mediaProjection!!.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopStreaming()
                    }
                },
                Handler(Looper.getMainLooper())
            )

            receiverAddress = InetAddress.getByName(receiverIp)

                        videoSocket = DatagramSocket()
            audioSocket = DatagramSocket()

            try {
                videoSocket!!.sendBufferSize = 1024 * 1024
            } catch (_: Exception) {
            }

            try {
                audioSocket!!.sendBufferSize = 256 * 1024
            } catch (_: Exception) {
            }

            running.set(true)

            setupVideoEncoder()

            val audioReady = setupAudioCaptureIfPermitted()

            val videoThread = Thread { videoEncodeLoop() }
            videoThread.start()

            if (audioReady) {
                Thread { audioEncodeLoop() }.start()
            }

            videoThread.join()

        } catch (_: Exception) {

            stopStreaming()
        }
    }

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

        if (longestEdge > maxDimension) {
            val scale = maxDimension.toFloat() / longestEdge
            width = (width * scale).toInt()
            height = (height * scale).toInt()
        }

        width -= width % 2
        height -= height % 2

        return Pair(width, height)
    }

    private fun setupVideoEncoder() {

        val (width, height) = computeCaptureSize()
        captureWidth = width
        captureHeight = height

        val format =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                captureWidth,
                captureHeight
            )

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, 2130708361)
        format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        videoEncoder =
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

        videoEncoder!!.configure(
            format,
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        inputSurface = videoEncoder!!.createInputSurface()
        videoEncoder!!.start()

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

    private fun setupAudioCaptureIfPermitted(): Boolean {

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        return try {

            val playbackConfig =
                AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

            val channelMask = AudioFormat.CHANNEL_IN_STEREO

            val audioFormat =
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(channelMask)
                    .build()

            val minBufferSize =
                AudioRecord.getMinBufferSize(
                    AUDIO_SAMPLE_RATE,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT
                )

            audioRecord =
                AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(minBufferSize * 2)
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .build()

            audioRecord!!.startRecording()

            val encoderFormat =
                MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    AUDIO_SAMPLE_RATE,
                    2
                )

            encoderFormat.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )

            encoderFormat.setInteger(
                MediaFormat.KEY_BIT_RATE,
                AUDIO_BITRATE
            )

            audioEncoder =
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)

            audioEncoder!!.configure(
                encoderFormat,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )

            audioEncoder!!.start()

            true

        } catch (_: Exception) {

            try { audioRecord?.release() } catch (_: Exception) {}
            try { audioEncoder?.release() } catch (_: Exception) {}
            audioRecord = null
            audioEncoder = null
            false
        }
    }

           private fun sendFragmented(
        socket: DatagramSocket,
        port: Int,
        type: Int,
        frameId: Int,
        flags: Int,
        presentationTimeUs: Long,
        data: ByteArray
    ) {

        val address = receiverAddress ?: return

        val fragmentCount =
            ((data.size + FRAGMENT_PAYLOAD_SIZE - 1) / FRAGMENT_PAYLOAD_SIZE)
                .coerceAtLeast(1)

        for (fragmentIndex in 0 until fragmentCount) {

            val start = fragmentIndex * FRAGMENT_PAYLOAD_SIZE
            val end = minOf(start + FRAGMENT_PAYLOAD_SIZE, data.size)
            val chunkSize = end - start

            val buffer = ByteBuffer.allocate(HEADER_SIZE + chunkSize)
            buffer.putInt(type)
            buffer.putInt(frameId)
            buffer.putInt(fragmentIndex)
            buffer.putInt(fragmentCount)
            buffer.putInt(flags)
            buffer.putLong(presentationTimeUs)
            buffer.putInt(data.size)
            buffer.put(data, start, chunkSize)

            try {
                val packet = DatagramPacket(
                    buffer.array(),
                    buffer.array().size,
                    address,
                    port
                )
                socket.send(packet)
            } catch (_: Exception) {
                // Best-effort - drop this fragment and move on.
            }
        }
    }
    private fun videoEncodeLoop() {

        val codec = videoEncoder ?: return
        val socket = videoSocket ?: return
        val info = MediaCodec.BufferInfo()
        var configurationSent = false

        while (running.get()) {

            val index = codec.dequeueOutputBuffer(info, 10_000)

            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                val format = codec.outputFormat
                val sps = format.getByteBuffer("csd-0")
                val pps = format.getByteBuffer("csd-1")

                if (sps != null && pps != null) {
                    cacheAndSendVideoConfig(socket, sps, pps)
                    configurationSent = true
                }

                continue
            }

            if (index < 0) continue

            val buffer = codec.getOutputBuffer(index)

            if (buffer != null && info.size > 0 && configurationSent) {

                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                val data = ByteArray(info.size)
                buffer.get(data)

                val isKeyFrame =
                    (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                if (isKeyFrame) {
                    val sps = cachedSps
                    val pps = cachedPps
                    if (sps != null && pps != null) {
                        sendVideoConfigPacket(socket, sps, pps)
                    }
                }

                videoFrameCounter++
                sendFragmented(
                    socket,
                    VIDEO_PORT,
                    TYPE_VIDEO_FRAME,
                    videoFrameCounter,
                    info.flags,
                    info.presentationTimeUs,
                    data
                )
            }

            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun audioEncodeLoop() {

        val codec = audioEncoder ?: return
        val record = audioRecord ?: return
        val socket = audioSocket ?: return

        val info = MediaCodec.BufferInfo()
        val pcmBuffer = ByteArray(4096)

        var configurationSent = false

        while (running.get()) {

            val inputIndex = codec.dequeueInputBuffer(10_000)

            if (inputIndex >= 0) {

                val inputBuffer = codec.getInputBuffer(inputIndex)

                if (inputBuffer != null) {

                    inputBuffer.clear()

                    val toRead =
                        minOf(pcmBuffer.size, inputBuffer.remaining())

                    val read = record.read(pcmBuffer, 0, toRead)

                    if (read > 0) {
                        inputBuffer.put(pcmBuffer, 0, read)
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            read,
                            System.nanoTime() / 1000,
                            0
                        )
                    } else {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            System.nanoTime() / 1000,
                            0
                        )
                    }
                }
            }

            val outputIndex = codec.dequeueOutputBuffer(info, 0)

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                if (!configurationSent) {
                    cacheAndSendAudioConfig(socket, codec.outputFormat)
                    configurationSent = true
                }

            } else if (outputIndex >= 0) {

                val buffer = codec.getOutputBuffer(outputIndex)

                if (buffer != null && info.size > 0 && configurationSent) {

                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    buffer.get(data)

                    val now = System.currentTimeMillis()
                    if (now - lastAudioConfigResendTime > AUDIO_CONFIG_RESEND_INTERVAL_MS) {
                        val csd0 = cachedAudioCsd0
                        if (csd0 != null) {
                            sendAudioConfigPacket(
                                socket,
                                cachedAudioSampleRate,
                                cachedAudioChannelCount,
                                csd0
                            )
                        }
                        lastAudioConfigResendTime = now
                    }

                    audioFrameCounter++
                    sendFragmented(
                        socket,
                        AUDIO_PORT,
                        TYPE_AUDIO_FRAME,
                        audioFrameCounter,
                        0,
                        info.presentationTimeUs,
                        data
                    )
                }

                codec.releaseOutputBuffer(outputIndex, false)
            }
        }
    }

    private fun cacheAndSendVideoConfig(
        socket: DatagramSocket,
        spsBuffer: ByteBuffer,
        ppsBuffer: ByteBuffer
    ) {

        val sps = ByteArray(spsBuffer.remaining())
        spsBuffer.get(sps)

        val pps = ByteArray(ppsBuffer.remaining())
        ppsBuffer.get(pps)

        cachedSps = sps
        cachedPps = pps

        sendVideoConfigPacket(socket, sps, pps)
    }

    private fun sendVideoConfigPacket(
        socket: DatagramSocket,
        sps: ByteArray,
        pps: ByteArray
    ) {

        val payload = ByteBuffer.allocate(16 + sps.size + pps.size)
        payload.putInt(captureWidth)
        payload.putInt(captureHeight)
        payload.putInt(sps.size)
        payload.put(sps)
        payload.putInt(pps.size)
        payload.put(pps)

        videoFrameCounter++
        sendFragmented(
            socket,
            VIDEO_PORT,
            TYPE_VIDEO_CONFIG,
            videoFrameCounter,
            0,
            0L,
            payload.array()
        )
    }

    private fun cacheAndSendAudioConfig(
        socket: DatagramSocket,
        format: MediaFormat
    ) {

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val csd0Buffer = format.getByteBuffer("csd-0")

        val csd0 = if (csd0Buffer != null) {
            val arr = ByteArray(csd0Buffer.remaining())
            csd0Buffer.get(arr)
            arr
        } else {
            ByteArray(0)
        }

        cachedAudioSampleRate = sampleRate
        cachedAudioChannelCount = channelCount
        cachedAudioCsd0 = csd0

        sendAudioConfigPacket(socket, sampleRate, channelCount, csd0)
        lastAudioConfigResendTime = System.currentTimeMillis()
    }

    private fun sendAudioConfigPacket(
        socket: DatagramSocket,
        sampleRate: Int,
        channelCount: Int,
        csd0: ByteArray
    ) {

        val payload = ByteBuffer.allocate(12 + csd0.size)
        payload.putInt(sampleRate)
        payload.putInt(channelCount)
        payload.putInt(csd0.size)
        payload.put(csd0)

        audioFrameCounter++
        sendFragmented(
            socket,
            AUDIO_PORT,
            TYPE_AUDIO_CONFIG,
            audioFrameCounter,
            0,
            0L,
            payload.array()
        )
    }

    private fun stopStreaming() {

        running.set(false)

        releaseWakeLock()

        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}

        try { videoEncoder?.stop() } catch (_: Exception) {}
        try { videoEncoder?.release() } catch (_: Exception) {}
        try { inputSurface?.release() } catch (_: Exception) {}

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}

        try { audioEncoder?.stop() } catch (_: Exception) {}
        try { audioEncoder?.release() } catch (_: Exception) {}

        try { videoSocket?.close() } catch (_: Exception) {}
        try { audioSocket?.close() } catch (_: Exception) {}

        virtualDisplay = null
        mediaProjection = null
        videoEncoder = null
        inputSurface = null
        audioRecord = null
        audioEncoder = null
        videoSocket = null
        audioSocket = null

        stopSelf()
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wireless Monitor",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {

        val stopIntent =
            Intent(this, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }

        val stopPendingIntent =
            PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE
            )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Wireless Monitor")
            .setContentText("Screen streaming is active - tap Stop Casting to end")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Casting",
                stopPendingIntent
            )
            .build()
    }
}