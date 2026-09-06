package com.screencast.receiver

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.Socket
import java.nio.ByteBuffer

class H264Receiver(
    private val socket: Socket,
    private val surface: Surface,
    private val status: (String) -> Unit,
    private val onVideoFormat: (Int, Int) -> Unit
) {
    fun run() {
        var decoder: MediaCodec? = null
        try {
            socket.tcpNoDelay = true
            socket.receiveBufferSize = 256 * 1024
            val input = DataInputStream(socket.getInputStream().buffered(64 * 1024))
            while (true) {
                val (type, data) = Protocol.readPacket(input)
                when (type) {
                    Protocol.TYPE_CONFIG -> {
                        decoder?.safeRelease()
                        val result = createVideoDecoder(data)
                        decoder = result.first
                        onVideoFormat(result.second.first, result.second.second)
                        status("Yayın başladı")
                    }
                    Protocol.TYPE_FRAME -> {
                        val d = decoder ?: continue
                        queueVideoFrame(d, data)
                        drainVideoDecoder(d)
                    }
                }
            }
        } catch (e: EOFException) {
            status("Video bağlantısı kapandı")
        } catch (e: Throwable) {
            status("Video hatası: ${e.javaClass.simpleName}: ${e.message ?: "bilinmeyen hata"}")
        } finally {
            decoder?.safeRelease()
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun createVideoDecoder(configData: ByteArray): Pair<MediaCodec, Pair<Int, Int>> {
        val input = DataInputStream(ByteArrayInputStream(configData))
        val width = input.readInt()
        val height = input.readInt()
        val csd0Size = input.readInt()
        require(csd0Size in 1..1_000_000) { "Invalid csd-0 size" }
        val csd0 = ByteArray(csd0Size).also { input.readFully(it) }
        val csd1Size = input.readInt()
        require(csd1Size in 0..1_000_000) { "Invalid csd-1 size" }
        val csd1 = ByteArray(csd1Size).also { input.readFully(it) }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            if (csd1.isNotEmpty()) setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            if (android.os.Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, surface, null, 0)
            start()
        }
        return decoder to (width to height)
    }

    private fun queueVideoFrame(decoder: MediaCodec, data: ByteArray) {
        if (data.size < 12) return
        val frame = DataInputStream(ByteArrayInputStream(data))
        val ptsUs = frame.readLong()
        val flags = frame.readInt()
        val payload = ByteArray(data.size - 12)
        frame.readFully(payload)
        val inputIndex = decoder.dequeueInputBuffer(2_000)
        if (inputIndex < 0) return // decoder yetişemiyorsa bu frame'i düşür, kuyruğu büyütme
        val buffer = decoder.getInputBuffer(inputIndex) ?: return
        if (payload.size > buffer.capacity()) return
        buffer.clear()
        buffer.put(payload)
        decoder.queueInputBuffer(inputIndex, 0, payload.size, ptsUs, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
    }

    private fun drainVideoDecoder(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var latest = -1
        while (true) {
            when (val output = decoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> break
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (output >= 0) {
                    if (latest >= 0) decoder.releaseOutputBuffer(latest, false)
                    latest = output
                }
            }
        }
        if (latest >= 0) decoder.releaseOutputBuffer(latest, true)
    }

    private fun MediaCodec.safeRelease() {
        try { stop() } catch (_: Throwable) {}
        try { release() } catch (_: Throwable) {}
    }
}
