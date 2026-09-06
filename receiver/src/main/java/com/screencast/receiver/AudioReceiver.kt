package com.screencast.receiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.Socket
import java.nio.ByteBuffer

class AudioReceiver(
    private val socket: Socket,
    private val status: (String) -> Unit
) {
    fun run() {
        var decoder: MediaCodec? = null
        var track: AudioTrack? = null
        try {
            socket.tcpNoDelay = true
            socket.receiveBufferSize = 64 * 1024
            val input = DataInputStream(socket.getInputStream().buffered(16 * 1024))
            while (true) {
                val (type, data) = Protocol.readPacket(input)
                when (type) {
                    Protocol.TYPE_CONFIG -> {
                        decoder?.safeRelease()
                        try { track?.stop() } catch (_: Throwable) {}
                        try { track?.release() } catch (_: Throwable) {}
                        val result = createAudioDecoder(data)
                        decoder = result.first
                        track = result.second
                    }
                    Protocol.TYPE_FRAME -> {
                        val d = decoder ?: continue
                        val t = track ?: continue
                        queueAudioFrame(d, data)
                        drainAudioDecoder(d, t)
                    }
                }
            }
        } catch (_: EOFException) {
            // Video devam edebilir; audio socket kapanması ana yayını bozmaz.
        } catch (e: Throwable) {
            status("Ses hatası: ${e.javaClass.simpleName}: ${e.message ?: "bilinmeyen hata"}")
        } finally {
            decoder?.safeRelease()
            try { track?.stop() } catch (_: Throwable) {}
            try { track?.release() } catch (_: Throwable) {}
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun createAudioDecoder(configData: ByteArray): Pair<MediaCodec, AudioTrack> {
        val input = DataInputStream(ByteArrayInputStream(configData))
        val sampleRate = input.readInt()
        val channels = input.readInt()
        val csdSize = input.readInt()
        require(csdSize in 0..1_000_000) { "Invalid audio csd size" }
        val csd = ByteArray(csdSize).also { input.readFully(it) }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            if (csd.isNotEmpty()) setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }

        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val targetBuffer = maxOf(minBuffer, sampleRate * channels * 2 / 20) // ~50 ms
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(targetBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val track = builder.build()
        track.play()
        return decoder to track
    }

    private fun queueAudioFrame(decoder: MediaCodec, data: ByteArray) {
        if (data.size < 12) return
        val frame = DataInputStream(ByteArrayInputStream(data))
        val ptsUs = frame.readLong()
        val flags = frame.readInt()
        val payload = ByteArray(data.size - 12)
        frame.readFully(payload)
        val inputIndex = decoder.dequeueInputBuffer(2_000)
        if (inputIndex < 0) return // ses kuyruğu büyümesin
        val buffer = decoder.getInputBuffer(inputIndex) ?: return
        if (payload.size > buffer.capacity()) return
        buffer.clear(); buffer.put(payload)
        decoder.queueInputBuffer(inputIndex, 0, payload.size, ptsUs, flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private fun drainAudioDecoder(decoder: MediaCodec, track: AudioTrack) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = decoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        val pcm = ByteArray(info.size)
                        outputBuffer.get(pcm)
                        if (Build.VERSION.SDK_INT >= 23) {
                            track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
                        } else {
                            @Suppress("DEPRECATION")
                            track.write(pcm, 0, pcm.size)
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    private fun MediaCodec.safeRelease() {
        try { stop() } catch (_: Throwable) {}
        try { release() } catch (_: Throwable) {}
    }
}
