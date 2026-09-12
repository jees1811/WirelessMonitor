package com.wirelessmonitor

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class VideoReceiver(
    private val surface: Surface
) {

    companion object {
        private const val PORT = 5000

        private const val PACKET_CONFIG = 0
        private const val PACKET_FRAME = 1

        private const val MAX_PACKET_SIZE = 8 * 1024 * 1024
    }

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var decoder: MediaCodec? = null

    private val running = AtomicBoolean(false)

    private var worker: Thread? = null

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

            serverSocket = ServerSocket(PORT)

            socket = serverSocket!!.accept()

            socket!!.tcpNoDelay = true

            val input =
                DataInputStream(
                    BufferedInputStream(
                        socket!!.getInputStream(),
                        1024 * 1024
                    )
                )

            /*
             * First packet must contain codec configuration.
             */
            val packetType =
                input.readInt()

            if (packetType != PACKET_CONFIG) {
                throw Exception(
                    "Expected H.264 configuration"
                )
            }

            val configSize =
                input.readInt()

            if (
                configSize <= 0 ||
                configSize > MAX_PACKET_SIZE
            ) {
                throw Exception(
                    "Invalid codec configuration"
                )
            }

            val config =
                ByteArray(configSize)

            input.readFully(config)

            /*
             * Configuration format:
             *
             * first 4 bytes = SPS length
             * next SPS bytes = SPS
             * next 4 bytes = PPS length
             * next PPS bytes = PPS
             */
            val configBuffer =
                ByteBuffer.wrap(config)

            val spsLength =
                configBuffer.int

            if (
                spsLength <= 0 ||
                spsLength > configBuffer.remaining()
            ) {
                throw Exception("Invalid SPS")
            }

            val sps =
                ByteArray(spsLength)

            configBuffer.get(sps)

            val ppsLength =
                configBuffer.int

            if (
                ppsLength <= 0 ||
                ppsLength > configBuffer.remaining()
            ) {
                throw Exception("Invalid PPS")
            }

            val pps =
                ByteArray(ppsLength)

            configBuffer.get(pps)

            /*
             * We use the actual stream dimensions sent
             * by the current first version:
             * 1280 × 720.
             */
            val format =
                MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    1280,
                    720
                )

            format.setByteBuffer(
                "csd-0",
                ByteBuffer.wrap(sps)
            )

            format.setByteBuffer(
                "csd-1",
                ByteBuffer.wrap(pps)
            )

            decoder =
                MediaCodec.createDecoderByType(
                    MediaFormat.MIMETYPE_VIDEO_AVC
                )

            decoder!!.configure(
                format,
                surface,
                null,
                0
            )

            decoder!!.start()

            receiveFrames(input)

        } catch (_: Exception) {

            // Connection ended or receiver stopped.

        } finally {

            cleanup()
        }
    }

    private fun receiveFrames(
        input: DataInputStream
    ) {

        val codec =
            decoder ?: return

        val bufferInfo =
            MediaCodec.BufferInfo()

        while (running.get()) {

            val packetType =
                input.readInt()

            if (
                packetType != PACKET_FRAME
            ) {
                break
            }

            val size =
                input.readInt()

            val flags =
                input.readInt()

            val presentationTimeUs =
                input.readLong()

            if (
                size <= 0 ||
                size > MAX_PACKET_SIZE
            ) {
                break
            }

            val data =
                ByteArray(size)

            input.readFully(data)

            val inputIndex =
                codec.dequeueInputBuffer(
                    10_000
                )

            if (inputIndex >= 0) {

                val inputBuffer =
                    codec.getInputBuffer(
                        inputIndex
                    )

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

            var outputIndex =
                codec.dequeueOutputBuffer(
                    bufferInfo,
                    0
                )

            while (outputIndex >= 0) {

                codec.releaseOutputBuffer(
                    outputIndex,
                    true
                )

                outputIndex =
                    codec.dequeueOutputBuffer(
                        bufferInfo,
                        0
                    )
            }
        }
    }

    fun stop() {

        running.set(false)

        cleanup()
    }

    private fun cleanup() {

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        try {
            decoder?.stop()
        } catch (_: Exception) {
        }

        try {
            decoder?.release()
        } catch (_: Exception) {
        }

        decoder = null
        socket = null
        serverSocket = null
    }
}
