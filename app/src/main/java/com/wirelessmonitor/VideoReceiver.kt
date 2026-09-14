package com.wirelessmonitor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class VideoReceiver(
    private val surface: Surface,
    private val onVideoSize: (width: Int, height: Int) -> Unit,
    private val onStatus: (String) -> Unit
) {

    companion object {
        const val PORT = 5000

        private const val PACKET_VIDEO_CONFIG = 0
        private const val PACKET_VIDEO_FRAME = 1
        private const val PACKET_AUDIO_CONFIG = 2
        private const val PACKET_AUDIO_FRAME = 3

        private const val MAX_PACKET_SIZE = 8 * 1024 * 1024
    }

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null

    private var videoDecoder: MediaCodec? = null
    private var audioDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    private val running = AtomicBoolean(false)

    private var worker: Thread? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {

        if (running.get()) return

        running.set(true)

        worker = Thread {
            runReceiver()
        }

        worker?.start()
    }

    private fun runReceiver() {

        try {

            mainHandler.post {
                onStatus("Waiting for the OnePlus 13 to connect...")
            }

            serverSocket = ServerSocket(PORT)

            socket = serverSocket!!.accept()

            socket!!.tcpNoDelay = true

            mainHandler.post {
                onStatus("Connected - waiting for video...")
            }

            val input =
                DataInputStream(
                    BufferedInputStream(
                        socket!!.getInputStream(),
                        1024 * 1024
                    )
                )

            while (running.get()) {

                when (val packetType = input.readInt()) {

                    PACKET_VIDEO_CONFIG -> handleVideoConfig(input)
                    PACKET_VIDEO_FRAME -> handleVideoFrame(input)
                    PACKET_AUDIO_CONFIG -> handleAudioConfig(input)
                    PACKET_AUDIO_FRAME -> handleAudioFrame(input)
                    else -> throw Exception("Unknown packet type $packetType")
                }
            }

        } catch (e: Exception) {

            mainHandler.post {
                onStatus(
                    "Disconnected: ${e.javaClass.simpleName}: ${e.message ?: "no details"}"
                )
            }

        } finally {

            cleanup()
        }
    }

    private fun handleVideoConfig(input: DataInputStream) {

        val videoWidth = input.readInt()
        val videoHeight = input.readInt()

        if (videoWidth <= 0 || videoHeight <= 0) {
            throw Exception("Invalid video size")
        }

        val configSize = input.readInt()

        if (configSize <= 0 || configSize > MAX_PACKET_SIZE) {
            throw Exception("Invalid codec configuration")
        }

        val config = ByteArray(configSize)
        input.readFully(config)

        val configBuffer = ByteBuffer.wrap(config)

        val spsLength = configBuffer.int

        if (spsLength <= 0 || spsLength > configBuffer.remaining()) {
            throw Exception("Invalid SPS")
        }

        val sps = ByteArray(spsLength)
        configBuffer.get(sps)

        val ppsLength = configBuffer.int

        if (ppsLength <= 0 || ppsLength > configBuffer.remaining()) {
            throw Exception("Invalid PPS")
        }

        val pps = ByteArray(ppsLength)
        configBuffer.get(pps)

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

        mainHandler.post {
            onVideoSize(videoWidth, videoHeight)
            onStatus("Connected")
        }
    }

    private fun handleVideoFrame(input: DataInputStream) {

        val size = input.readInt()
        val flags = input.readInt()
        val presentationTimeUs = input.readLong()

        if (size <= 0 || size > MAX_PACKET_SIZE) {
            throw Exception("Invalid video frame")
        }

        val data = ByteArray(size)
        input.readFully(data)

        val codec = videoDecoder ?: return

        val inputIndex = codec.dequeueInputBuffer(10_000)

        if (inputIndex >= 0) {

            val inputBuffer = codec.getInputBuffer(inputIndex)

            if (inputBuffer != null) {
                inputBuffer.clear()
                inputBuffer.put(data)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    data.size,
                    presentationTimeUs,
                    flags
                )
            }
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)

        while (outputIndex >= 0) {
            codec.releaseOutputBuffer(outputIndex, true)
            outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    private fun handleAudioConfig(input: DataInputStream) {

        val sampleRate = input.readInt()
        val channelCount = input.readInt()

        val csd0Size = input.readInt()

        if (csd0Size < 0 || csd0Size > MAX_PACKET_SIZE) {
            throw Exception("Invalid audio configuration")
        }

        val csd0 = ByteArray(csd0Size)
        if (csd0Size > 0) input.readFully(csd0)

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

    private fun handleAudioFrame(input: DataInputStream) {

        val size = input.readInt()
        val presentationTimeUs = input.readLong()

        if (size <= 0 || size > MAX_PACKET_SIZE) {
            throw Exception("Invalid audio frame")
        }

        val data = ByteArray(size)
        input.readFully(data)

        val codec = audioDecoder ?: return

        val inputIndex = codec.dequeueInputBuffer(10_000)

        if (inputIndex >= 0) {

            val inputBuffer = codec.getInputBuffer(inputIndex)

            if (inputBuffer != null) {
                inputBuffer.clear()
                inputBuffer.put(data)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    data.size,
                    presentationTimeUs,
                    0
                )
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

        cleanup()
    }

    private fun cleanup() {

        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}

        try { videoDecoder?.stop() } catch (_: Exception) {}
        try { videoDecoder?.release() } catch (_: Exception) {}

        try { audioDecoder?.stop() } catch (_: Exception) {}
        try { audioDecoder?.release() } catch (_: Exception) {}

        try { audioTrack?.stop() } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}

        videoDecoder = null
        audioDecoder = null
        audioTrack = null
        socket = null
        serverSocket = null
    }
}