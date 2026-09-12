package com.wirelessmonitor

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class VideoReceiver(
    private val surface: Surface
) {

    companion object {
        private const val PORT = 5000
        private const val WIDTH = 1280
        private const val HEIGHT = 720
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
            socket!!.receiveBufferSize = 1024 * 1024

            val input =
                DataInputStream(
                    BufferedInputStream(
                        socket!!.getInputStream(),
                        1024 * 1024
                    )
                )

            decoder = MediaCodec.createDecoderByType(
                MediaFormat.MIMETYPE_VIDEO_AVC
            )

            val format =
                MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC,
                    WIDTH,
                    HEIGHT
                )

            format.setInteger(
                MediaFormat.KEY_MAX_INPUT_SIZE,
                2 * 1024 * 1024
            )

            decoder!!.configure(
                format,
                surface,
                null,
                0
            )

            decoder!!.start()

            val bufferInfo = MediaCodec.BufferInfo()

            while (running.get()) {

                val size = input.readInt()

                if (size <= 0 || size > 4 * 1024 * 1024) {
                    break
                }

                val flags = input.readInt()

                val data = ByteArray(size)

                input.readFully(data)

                val codec = decoder ?: break

                val inputIndex =
                    codec.dequeueInputBuffer(10_000)

                if (inputIndex >= 0) {

                    val inputBuffer =
                        codec.getInputBuffer(inputIndex)

                    inputBuffer?.clear()
                    inputBuffer?.put(data)

                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        data.size,
                        System.nanoTime() / 1000,
                        flags
                    )
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

        } catch (_: Exception) {
            // Connection closed or receiver stopped.
        } finally {
            cleanup()
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
