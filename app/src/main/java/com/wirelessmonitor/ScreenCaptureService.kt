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
import java.io.DataOutputStream
import java.net.Socket
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

        private const val PACKET_VIDEO_CONFIG = 0
        private const val PACKET_VIDEO_FRAME = 1
        private const val PACKET_AUDIO_CONFIG = 2
        private const val PACKET_AUDIO_FRAME = 3

        private const val RECONNECT_DELAY_MS = 2000L
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var videoEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null

    private val videoLock = Any()
    @Volatile private var videoSocket: Socket? = null
    @Volatile private var videoOutput: DataOutputStream? = null
    @Volatile private var videoReconnecting = false
    private var cachedSps: ByteArray? = null
    private var cachedPps: ByteArray? = null

    private val audioLock = Any()
    @Volatile private var audioSocket: Socket? = null
    @Volatile private var audioOutput: DataOutputStream? = null
    @Volatile private var audioReconnecting = false
    private var cachedAudioSampleRate = 0
    private var cachedAudioChannelCount = 0
    private var cachedAudioCsd0: ByteArray? = null

    private var receiverIpAddress = ""

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

        receiverIpAddress = receiverIp

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

            videoSocket = Socket(receiverIp, VIDEO_PORT)
            videoSocket!!.tcpNoDelay = true
            videoOutput = DataOutputStream(videoSocket!!.getOutputStream())

            running.set(true)

            setupVideoEncoder()

            val audioReady = setupAudioCaptureIfPermitted()

            if (audioReady) {
                try {
                    audioSocket = Socket(receiverIp, AUDIO_PORT)
                    audioSocket!!.tcpNoDelay = true
                    audioOutput = DataOutputStream(audioSocket!!.getOutputStream())
                } catch (_: Exception) {
                }
            }

            val videoThread = Thread { videoEncodeLoop() }
            videoThread.start()

            if (audioReady && audioOutput != null) {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                format.setInteger(MediaFormat.KEY_LATENCY, 0)
            } catch (_: Exception) {
            }
        }

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

    private fun videoEncodeLoop() {

        val codec = videoEncoder ?: return
        val info = MediaCodec.BufferInfo()
        var configurationSent = false

        while (running.get()) {

            val index = codec.dequeueOutputBuffer(info, 10_000)

            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                val format = codec.outputFormat
                val sps = format.getByteBuffer("csd-0")
                val pps = format.getByteBuffer("csd-1")

                if (sps != null && pps != null && !configurationSent) {
                    sendVideoConfiguration(sps, pps)
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
                sendVideoFrame(data, info.flags, info.presentationTimeUs)
            }

            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun audioEncodeLoop() {

        val codec = audioEncoder ?: return
        val record = audioRecord ?: return

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
                    sendAudioConfiguration(codec.outputFormat)
                    configurationSent = true
                }

            } else if (outputIndex >= 0) {

                val buffer = codec.getOutputBuffer(outputIndex)

                if (buffer != null && info.size > 0 && configurationSent) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    buffer.get(data)
                    sendAudioFrame(data, info.presentationTimeUs)
                }

                codec.releaseOutputBuffer(outputIndex, false)
            }
        }
    }

    private fun sendVideoConfiguration(
        spsBuffer: ByteBuffer,
        ppsBuffer: ByteBuffer
    ) {

        val sps = ByteArray(spsBuffer.remaining())
        spsBuffer.get(sps)

        val pps = ByteArray(ppsBuffer.remaining())
        ppsBuffer.get(pps)

        cachedSps = sps
        cachedPps = pps

        writeVideoConfiguration(sps, pps)
    }

    private fun writeVideoConfiguration(sps: ByteArray, pps: ByteArray) {

        try {

            synchronized(videoLock) {

                videoOutput?.writeInt(PACKET_VIDEO_CONFIG)
                videoOutput?.writeInt(captureWidth)
                videoOutput?.writeInt(captureHeight)

                val totalSize = 4 + sps.size + 4 + pps.size
                videoOutput?.writeInt(totalSize)

                videoOutput?.writeInt(sps.size)
                videoOutput?.write(sps)

                videoOutput?.writeInt(pps.size)
                videoOutput?.write(pps)

                videoOutput?.flush()
            }

        } catch (_: Exception) {
            attemptVideoReconnect()
        }
    }

    private fun sendVideoFrame(
        data: ByteArray,
        flags: Int,
        presentationTimeUs: Long
    ) {

        try {

            synchronized(videoLock) {
                videoOutput?.writeInt(PACKET_VIDEO_FRAME)
                videoOutput?.writeInt(data.size)
                videoOutput?.writeInt(flags)
                videoOutput?.writeLong(presentationTimeUs)
                videoOutput?.write(data)
                videoOutput?.flush()
            }

        } catch (_: Exception) {
            attemptVideoReconnect()
        }
    }

    private fun sendAudioConfiguration(format: MediaFormat) {

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

        writeAudioConfiguration(sampleRate, channelCount, csd0)
    }

    private fun writeAudioConfiguration(
        sampleRate: Int,
        channelCount: Int,
        csd0: ByteArray
    ) {

        try {

            synchronized(audioLock) {
                audioOutput?.writeInt(PACKET_AUDIO_CONFIG)
                audioOutput?.writeInt(sampleRate)
                audioOutput?.writeInt(channelCount)
                audioOutput?.writeInt(csd0.size)
                audioOutput?.write(csd0)
                audioOutput?.flush()
            }

        } catch (_: Exception) {
            attemptAudioReconnect()
        }
    }

    private fun sendAudioFrame(
        data: ByteArray,
        presentationTimeUs: Long
    ) {

        try {

            synchronized(audioLock) {
                audioOutput?.writeInt(PACKET_AUDIO_FRAME)
                audioOutput?.writeInt(data.size)
                audioOutput?.writeLong(presentationTimeUs)
                audioOutput?.write(data)
                audioOutput?.flush()
            }

        } catch (_: Exception) {
            attemptAudioReconnect()
        }
    }

    private fun attemptVideoReconnect() {

        synchronized(videoLock) {
            if (videoReconnecting) return
            videoReconnecting = true
        }

        Thread {

            while (running.get()) {

                try {

                    val newSocket = Socket(receiverIpAddress, VIDEO_PORT)
                    newSocket.tcpNoDelay = true
                    val newOutput = DataOutputStream(newSocket.getOutputStream())

                    synchronized(videoLock) {
                        try { videoSocket?.close() } catch (_: Exception) {}
                        videoSocket = newSocket
                        videoOutput = newOutput
                    }

                    val sps = cachedSps
                    val pps = cachedPps

                    if (sps != null && pps != null) {
                        writeVideoConfiguration(sps, pps)
                    }

                    videoReconnecting = false
                    return@Thread

                } catch (_: Exception) {
                    try { Thread.sleep(RECONNECT_DELAY_MS) } catch (_: Exception) {}
                }
            }

            videoReconnecting = false

        }.start()
    }

    private fun attemptAudioReconnect() {

        synchronized(audioLock) {
            if (audioReconnecting) return
            audioReconnecting = true
        }

        Thread {

            while (running.get()) {

                try {

                    val newSocket = Socket(receiverIpAddress, AUDIO_PORT)
                    newSocket.tcpNoDelay = true
                    val newOutput = DataOutputStream(newSocket.getOutputStream())

                    synchronized(audioLock) {
                        try { audioSocket?.close() } catch (_: Exception) {}
                        audioSocket = newSocket
                        audioOutput = newOutput
                    }

                    val csd0 = cachedAudioCsd0

                    if (csd0 != null && cachedAudioSampleRate > 0) {
                        writeAudioConfiguration(
                            cachedAudioSampleRate,
                            cachedAudioChannelCount,
                            csd0
                        )
                    }

                    audioReconnecting = false
                    return@Thread

                } catch (_: Exception) {
                    try { Thread.sleep(RECONNECT_DELAY_MS) } catch (_: Exception) {}
                }
            }

            audioReconnecting = false

        }.start()
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

        try { videoOutput?.close() } catch (_: Exception) {}
        try { videoSocket?.close() } catch (_: Exception) {}

        try { audioOutput?.close() } catch (_: Exception) {}
        try { audioSocket?.close() } catch (_: Exception) {}

        virtualDisplay = null
        mediaProjection = null
        videoEncoder = null
        inputSurface = null
        audioRecord = null
        audioEncoder = null
        videoOutput = null
        videoSocket = null
        audioOutput = null
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