package com.wirelessmonitor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class VideoReceiver(
    private val surface: Surface,
    private val onVideoSize: (width: Int, height: Int) -> Unit,
    private val onStatus: (String) -> Unit
) {

    companion object {
        const val VIDEO_PORT = 5000
        const val AUDIO_PORT = 5001

        private const val TYPE_VIDEO_CONFIG = 10
        private const val TYPE_VIDEO_FRAME = 11
        private const val TYPE_AUDIO_CONFIG = 20
        private const val TYPE_AUDIO_FRAME = 21

        private const val HEADER_SIZE = 32
        private const val RECEIVE_BUFFER_SIZE = 2048
        private const val MAX_ASSEMBLED_SIZE = 8 * 1024 * 1024

        private const val STALE_TIMEOUT_MS = 3000L
    }

    private var videoSocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null

    private var videoDecoder: MediaCodec? = null
    private var audioDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    private val running = AtomicBoolean(false)

    private var videoWorker: Thread? = null
    private var audioWorker: Thread? = null
    private var watchdogWorker: Thread? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastVideoPacketTime = 0L
    @Volatile private var videoConnected = false

    private var currentVideoFrameId = -1
    private var currentVideoFragments: Array<ByteArray?>? = null
    private var currentVideoFragmentsReceived = 0
    private var currentVideoTotalSize = 0

    private var waitingForKeyframe = true

    private var currentAudioFrameId = -1
    private var currentAudioFragments: Array<ByteArray?>? = null
    private var currentAudioFragmentsReceived = 0
    private var currentAudioTotalSize = 0

    fun start() {

        if (running.get()) return

        running.set(true)

        videoWorker = Thread { runVideoReceiver() }
        videoWorker?.start()

        audioWorker = Thread { runAudioReceiver() }
        audioWorker?.start()

        watchdogWorker = Thread { runWatchdog() }
        watchdogWorker?.start()
    }

    private fun runWatchdog() {

        while (running.get()) {

            try { Thread.sleep(500) } catch (_: Exception) { break }

            if (
                videoConnected &&
                System.currentTimeMillis() - lastVideoPacketTime > STALE_TIMEOUT_MS
            ) {
                videoConnected = false
                mainHandler.post {
                    onStatus("Waiting for the OnePlus 13 to connect...")
                }
            }
        }
    }

    private fun runVideoReceiver() {

        try {

            videoSocket = DatagramSocket(VIDEO_PORT)

            try {
                videoSocket!!.receiveBufferSize = 1024 * 1024
            } catch (_: Exception) {
            }

            mainHandler.post {
                onStatus("Waiting for the OnePlus 13 to connect...")
            }

            val buffer = ByteArray(RECEIVE_BUFFER_SIZE)

            while (running.get()) {

                val packet = DatagramPacket(buffer, buffer.size)

                try {
                    videoSocket!!.receive(packet)
                } catch (_: Exception) {
                    break
                }

                lastVideoPacketTime = System.currentTimeMillis()

                if (!videoConnected) {
                    videoConnected = true
                    mainHandler.post { onStatus("Connected") }
                }

                try {
                    handleVideoPacket(packet.data, packet.length)
                } catch (_: Exception) {
                }
            }

        } catch (e: Exception) {

            mainHandler.post {
                onStatus("Error: ${e.javaClass.simpleName}: ${e.message ?: "no details"}")
            }

        } finally {

            try { videoSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun handleVideoPacket(data: ByteArray, length: Int) {

        if (length < HEADER_SIZE) return

        val header = ByteBuffer.wrap(data, 0, HEADER_SIZE)
        val type = header.int
        val frameId = header.int
        val fragmentIndex = header.int
        val fragmentCount = header.int
        val flags = header.int
        val pts = header.long
        val totalSize = header.int

        if (totalSize <= 0 || totalSize > MAX_ASSEMBLED_SIZE) return
        if (fragmentCount <= 0 || fragmentIndex < 0 || fragmentIndex >= fragmentCount) return

        val payloadLength = length - HEADER_SIZE
        if (payloadLength <= 0) return

        if (currentVideoFrameId != frameId) {

            val previousFragments = currentVideoFragments
            if (
                previousFragments != null &&
                currentVideoFragmentsReceived < previousFragments.size
            ) {
                waitingForKeyframe = true
            }

            currentVideoFrameId = frameId
            currentVideoFragments = arrayOfNulls(fragmentCount)
            currentVideoFragmentsReceived = 0
            currentVideoTotalSize = totalSize
        }

        val fragments = currentVideoFragments ?: return
        if (fragmentIndex >= fragments.size) return

        if (fragments[fragmentIndex] == null) {
            val payload = ByteArray(payloadLength)
            System.arraycopy(data, HEADER_SIZE, payload, 0, payloadLength)
            fragments[fragmentIndex] = payload
            currentVideoFragmentsReceived++
        }

        if (currentVideoFragmentsReceived != fragments.size) return

        val complete = ByteArray(currentVideoTotalSize)
        var offset = 0
        for (fragment in fragments) {
            if (fragment == null) return
            System.arraycopy(fragment, 0, complete, offset, fragment.size)
            offset += fragment.size
        }

        currentVideoFrameId = -1
        currentVideoFragments = null

        when (type) {
            TYPE_VIDEO_CONFIG -> processVideoConfig(complete)
            TYPE_VIDEO_FRAME -> {

                val isKeyFrame = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                if (waitingForKeyframe) {
                    if (isKeyFrame) {
                        waitingForKeyframe = false
                        processVideoFrame(complete, flags, pts)
                    }
                } else {
                    processVideoFrame(complete, flags, pts)
                }
            }
        }
    }

    private fun processVideoConfig(data: ByteArray) {

        val buffer = ByteBuffer.wrap(data)

        val videoWidth = buffer.int
        val videoHeight = buffer.int

        if (videoWidth <= 0 || videoHeight <= 0) return

        val spsLength = buffer.int
        if (spsLength <= 0 || spsLength > buffer.remaining()) return
        val sps = ByteArray(spsLength)
        buffer.get(sps)

        val ppsLength = buffer.int
        if (ppsLength <= 0 || ppsLength > buffer.remaining()) return
        val pps = ByteArray(ppsLength)
        buffer.get(pps)

        val format =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                videoWidth,
                videoHeight
            )

        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))

        try { videoDecoder?.stop() } catch (_: Exception) {}
        try { videoDecoder?.release() } catch (_: Exception) {}

        videoDecoder =
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

        videoDecoder!!.configure(format, surface, null, 0)
        videoDecoder!!.start()

        waitingForKeyframe = true

        mainHandler.post {
            onVideoSize(videoWidth, videoHeight)
        }
    }

    private fun processVideoFrame(data: ByteArray, flags: Int, presentationTimeUs: Long) {

        val codec = videoDecoder ?: return

        val inputIndex = codec.dequeueInputBuffer(10_000)

        if (inputIndex >= 0) {

            val inputBuffer = codec.getInputBuffer(inputIndex)

            if (inputBuffer != null) {
                inputBuffer.clear()
                inputBuffer.put(data)
                codec.queueInputBuffer(inputIndex, 0, data.size, presentationTimeUs, flags)
            }
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)

        while (outputIndex >= 0) {
            codec.releaseOutputBuffer(outputIndex, true)
            outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    private fun runAudioReceiver() {

        try {

            audioSocket = DatagramSocket(AUDIO_PORT)

            try {
                audioSocket!!.receiveBufferSize = 256 * 1024
            } catch (_: Exception) {
            }

            val buffer = ByteArray(RECEIVE_BUFFER_SIZE)

            while (running.get()) {

                val packet = DatagramPacket(buffer, buffer.size)

                try {
                    audioSocket!!.receive(packet)
                } catch (_: Exception) {
                    break
                }

                try {
                    handleAudioPacket(packet.data, packet.length)
                } catch (_: Exception) {
                }
            }

        } catch (_: Exception) {

        } finally {

            try { audioSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun handleAudioPacket(data: ByteArray, length: Int) {

        if (length < HEADER_SIZE) return

        val header = ByteBuffer.wrap(data, 0, HEADER_SIZE)
        val type = header.int
        val frameId = header.int
        val fragmentIndex = header.int
        val fragmentCount = header.int
        header.int
        val pts = header.long
        val totalSize = header.int

        if (totalSize <= 0 || totalSize > MAX_ASSEMBLED_SIZE) return
        if (fragmentCount <= 0 || fragmentIndex < 0 || fragmentIndex >= fragmentCount) return

        val payloadLength = length - HEADER_SIZE
        if (payloadLength <= 0) return

        if (currentAudioFrameId != frameId) {
            currentAudioFrameId = frameId
            currentAudioFragments = arrayOfNulls(fragmentCount)
            currentAudioFragmentsReceived = 0
            currentAudioTotalSize = totalSize
        }

        val fragments = currentAudioFragments ?: return
        if (fragmentIndex >= fragments.size) return

        if (fragments[fragmentIndex] == null) {
            val payload = ByteArray(payloadLength)
            System.arraycopy(data, HEADER_SIZE, payload, 0, payloadLength)
            fragments[fragmentIndex] = payload
            currentAudioFragmentsReceived++
        }

        if (currentAudioFragmentsReceived != fragments.size) return

        val complete = ByteArray(currentAudioTotalSize)
        var offset = 0
        for (fragment in fragments) {
            if (fragment == null) return
            System.arraycopy(fragment, 0, complete, offset, fragment.size)
            offset += fragment.size
        }

        currentAudioFrameId = -1
        currentAudioFragments = null

        when (type) {
            TYPE_AUDIO_CONFIG -> processAudioConfig(complete)
            TYPE_AUDIO_FRAME -> processAudioFrame(complete, pts)
        }
    }

    private fun processAudioConfig(data: ByteArray) {

        val buffer = ByteBuffer.wrap(data)

        val sampleRate = buffer.int
        val channelCount = buffer.int
        val csd0Size = buffer.int

        if (csd0Size < 0 || csd0Size > buffer.remaining()) return

        val csd0 = ByteArray(csd0Size)
        if (csd0Size > 0) buffer.get(csd0)

        val format =
            MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRate,
                channelCount
            )

        if (csd0Size > 0) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        }

        try { audioDecoder?.stop() } catch (_: Exception) {}
        try { audioDecoder?.release() } catch (_: Exception) {}

        audioDecoder =
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)

        audioDecoder!!.configure(format, null, null, 0)
        audioDecoder!!.start()

        val channelConfig = if (channelCount >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }

        val minBufferSize =
            AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT
            )

        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}

        audioTrack =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

        audioTrack!!.play()
    }

    private fun processAudioFrame(data: ByteArray, presentationTimeUs: Long) {

        val codec = audioDecoder ?: return

        val inputIndex = codec.dequeueInputBuffer(10_000)

        if (inputIndex >= 0) {

            val inputBuffer = codec.getInputBuffer(inputIndex)

            if (inputBuffer != null) {
                inputBuffer.clear()
                inputBuffer.put(data)
                codec.queueInputBuffer(inputIndex, 0, data.size, presentationTimeUs, 0)
            }
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)

        while (outputIndex >= 0) {

            val outputBuffer = codec.getOutputBuffer(outputIndex)

            if (outputBuffer != null && bufferInfo.size > 0) {
                val pcm = ByteArray(bufferInfo.size)
                outputBuffer.position(bufferInfo.offset)
                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                outputBuffer.get(pcm)
                audioTrack?.write(pcm, 0, pcm.size)
            }

            codec.releaseOutputBuffer(outputIndex, false)
            outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    fun stop() {

        running.set(false)

        try { videoSocket?.close() } catch (_: Exception) {}
        try { audioSocket?.close() } catch (_: Exception) {}

        try { videoDecoder?.stop() } catch (_: Exception) {}
        try { videoDecoder?.release() } catch (_: Exception) {}
        videoDecoder = null

        try { audioDecoder?.stop() } catch (_: Exception) {}
        try { audioDecoder?.release() } catch (_: Exception) {}
        audioDecoder = null

        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }
}