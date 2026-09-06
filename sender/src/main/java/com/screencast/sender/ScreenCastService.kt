package com.screencast.sender

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCastService : Service() {
    private var projection: MediaProjection? = null
    private val running = AtomicBoolean(false)

    private var videoSocket: Socket? = null
    private var videoOut: DataOutputStream? = null
    private var audioSocket: Socket? = null
    private var audioOut: DataOutputStream? = null

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    @Volatile private var activeVirtualDisplay: VirtualDisplay? = null
    @Volatile private var activeVideoCodec: MediaCodec? = null
    @Volatile private var activeVideoSurface: Surface? = null
    @Volatile private var activeAudioRecord: AudioRecord? = null
    @Volatile private var activeAudioCodec: MediaCodec? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1, notification("ScreenCast yayın başlatılıyor"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.get()) return START_NOT_STICKY
        val data = intent?.getParcelableExtra<Intent>("projectionData") ?: return START_NOT_STICKY
        val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val ip = intent.getStringExtra("receiverIp") ?: return START_NOT_STICKY
        val audioEnabled = intent.getBooleanExtra("audioEnabled", false)
        Thread({ startStreaming(resultCode, data, ip, audioEnabled) }, "ScreenCastMain").start()
        return START_NOT_STICKY
    }

    private fun startStreaming(resultCode: Int, data: Intent, ip: String, audioEnabled: Boolean) {
        try {
            videoSocket = createLowLatencySocket(ip, Protocol.VIDEO_PORT)
            videoOut = DataOutputStream(videoSocket!!.getOutputStream().buffered(64 * 1024))

            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = pm.getMediaProjection(resultCode, data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { running.set(false) }
            }, Handler(Looper.getMainLooper()))

            running.set(true)
            updateNotification(if (audioEnabled && Build.VERSION.SDK_INT >= 29) "Düşük gecikmeli görüntü + ses aktarılıyor" else "Düşük gecikmeli görüntü aktarılıyor")

            videoThread = Thread({ videoLoop() }, "ScreenCastVideo").also { it.start() }
            if (audioEnabled && Build.VERSION.SDK_INT >= 29) {
                audioThread = Thread({ audioLoop(ip) }, "ScreenCastAudio").also { it.start() }
            }

            videoThread?.join()
            running.set(false)
            audioThread?.join(1000)
        } catch (e: Throwable) {
            Log.e(TAG, "Streaming failed", e)
            updateNotification("Yayın hatası: ${e.javaClass.simpleName}: ${e.message ?: "bilinmeyen hata"}")
        } finally {
            running.set(false)
            cleanup()
            stopSelf()
        }
    }

    private fun createLowLatencySocket(ip: String, port: Int): Socket = Socket().apply {
        tcpNoDelay = true
        keepAlive = true
        sendBufferSize = 256 * 1024
        receiveBufferSize = 128 * 1024
        connect(InetSocketAddress(ip, port), 4000)
    }

    private fun videoLoop() {
        var virtualDisplay: VirtualDisplay? = null
        try {
            while (running.get()) {
                val (width, height) = desiredVideoSize()
                val dpi = resources.displayMetrics.densityDpi
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0)
                    if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }

                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                activeVideoCodec = codec
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                activeVideoSurface = surface
                codec.start()

                if (virtualDisplay == null) {
                    virtualDisplay = projection!!.createVirtualDisplay(
                        "ScreenCast", width, height, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface, null, null
                    )
                    activeVirtualDisplay = virtualDisplay
                } else {
                    virtualDisplay.resize(width, height, dpi)
                    virtualDisplay.surface = surface
                }

                try {
                    streamConfiguredVideoCodec(codec, width, height)
                } finally {
                    try { virtualDisplay.surface = null } catch (_: Throwable) {}
                    releaseVideoEncoderOnly()
                }
            }
        } finally {
            try { virtualDisplay?.release() } catch (_: Throwable) {}
            if (activeVirtualDisplay === virtualDisplay) activeVirtualDisplay = null
        }
    }

    private fun streamConfiguredVideoCodec(codec: MediaCodec, width: Int, height: Int) {
        val info = MediaCodec.BufferInfo()
        var configSent = false
        while (running.get()) {
            val current = desiredVideoSize()
            if (current.first != width || current.second != height) return

            when (val index = codec.dequeueOutputBuffer(info, 5_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outputFormat = codec.outputFormat
                    val csd0 = outputFormat.getByteBuffer("csd-0")?.toByteArraySafe() ?: ByteArray(0)
                    val csd1 = outputFormat.getByteBuffer("csd-1")?.toByteArraySafe() ?: ByteArray(0)
                    if (csd0.isEmpty()) throw IllegalStateException("Encoder SPS bilgisi üretmedi")
                    sendVideoConfig(width, height, csd0, csd1)
                    configSent = true
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && configSent
                    ) {
                        sendVideoFrame(buffer.readBytes(info.offset, info.size), info.presentationTimeUs, info.flags)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun desiredVideoSize(): Pair<Int, Int> =
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) 720 to 1280 else 1280 to 720

    private fun sendVideoConfig(width: Int, height: Int, csd0: ByteArray, csd1: ByteArray) {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { data ->
            data.writeInt(width)
            data.writeInt(height)
            data.writeInt(csd0.size); data.write(csd0)
            data.writeInt(csd1.size); data.write(csd1)
        }
        Protocol.writePacket(videoOut!!, Protocol.TYPE_CONFIG, bos.toByteArray())
    }

    private fun sendVideoFrame(bytes: ByteArray, ptsUs: Long, flags: Int) {
        val bos = ByteArrayOutputStream(bytes.size + 12)
        DataOutputStream(bos).use { data ->
            data.writeLong(ptsUs)
            data.writeInt(flags)
            data.write(bytes)
        }
        Protocol.writePacket(videoOut!!, Protocol.TYPE_FRAME, bos.toByteArray())
    }

    private fun audioLoop(ip: String) {
        if (Build.VERSION.SDK_INT < 29) return
        var record: AudioRecord? = null
        var codec: MediaCodec? = null
        try {
            audioSocket = createLowLatencySocket(ip, Protocol.AUDIO_PORT).apply { sendBufferSize = 64 * 1024 }
            audioOut = DataOutputStream(audioSocket!!.getOutputStream().buffered(16 * 1024))

            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val bytesPerFrame = 4
            val minBuffer = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            val recordBuffer = maxOf(minBuffer, AUDIO_SAMPLE_RATE * bytesPerFrame / 25) // ~40 ms

            record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(recordBuffer)
                .build()
            activeAudioRecord = record

            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, recordBuffer)
                if (Build.VERSION.SDK_INT >= 23) setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            activeAudioCodec = codec
            codec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            record.startRecording()

            val info = MediaCodec.BufferInfo()
            val startUs = System.nanoTime() / 1000L
            var configSent = false

            while (running.get()) {
                val inputIndex = codec.dequeueInputBuffer(5_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        val read = record.read(inputBuffer, minOf(inputBuffer.capacity(), recordBuffer), AudioRecord.READ_BLOCKING)
                        if (read > 0) {
                            codec.queueInputBuffer(inputIndex, 0, read, System.nanoTime() / 1000L - startUs, 0)
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                        }
                    }
                }

                while (true) {
                    when (val outputIndex = codec.dequeueOutputBuffer(info, 0)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val csd0 = codec.outputFormat.getByteBuffer("csd-0")?.toByteArraySafe() ?: ByteArray(0)
                            sendAudioConfig(AUDIO_SAMPLE_RATE, AUDIO_CHANNELS, csd0)
                            configSent = true
                        }
                        else -> if (outputIndex >= 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && info.size > 0 &&
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && configSent
                            ) {
                                sendAudioFrame(outputBuffer.readBytes(info.offset, info.size), info.presentationTimeUs, info.flags)
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Audio capture permission/eligibility problem", e)
            updateNotification("Görüntü aktarılıyor - ses yakalanamadı")
        } catch (e: Throwable) {
            Log.e(TAG, "Audio streaming failed", e)
            updateNotification("Görüntü aktarılıyor - ses hatası: ${e.javaClass.simpleName}")
        } finally {
            try { record?.stop() } catch (_: Throwable) {}
            try { record?.release() } catch (_: Throwable) {}
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { audioOut?.close() } catch (_: Throwable) {}
            try { audioSocket?.close() } catch (_: Throwable) {}
            audioOut = null; audioSocket = null
            if (activeAudioRecord === record) activeAudioRecord = null
            if (activeAudioCodec === codec) activeAudioCodec = null
        }
    }

    private fun sendAudioConfig(sampleRate: Int, channels: Int, csd0: ByteArray) {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { data ->
            data.writeInt(sampleRate)
            data.writeInt(channels)
            data.writeInt(csd0.size)
            data.write(csd0)
        }
        Protocol.writePacket(audioOut!!, Protocol.TYPE_CONFIG, bos.toByteArray())
    }

    private fun sendAudioFrame(bytes: ByteArray, ptsUs: Long, flags: Int) {
        val bos = ByteArrayOutputStream(bytes.size + 12)
        DataOutputStream(bos).use { data ->
            data.writeLong(ptsUs)
            data.writeInt(flags)
            data.write(bytes)
        }
        Protocol.writePacket(audioOut!!, Protocol.TYPE_FRAME, bos.toByteArray())
    }

    override fun onDestroy() {
        running.set(false)
        cleanup()
        super.onDestroy()
    }

    private fun releaseVideoEncoderOnly() {
        val surface = activeVideoSurface
        activeVideoSurface = null
        try { surface?.release() } catch (_: Throwable) {}
        val codec = activeVideoCodec
        activeVideoCodec = null
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
    }

    private fun releaseVideoPipeline() {
        val vd = activeVirtualDisplay
        activeVirtualDisplay = null
        try { vd?.surface = null } catch (_: Throwable) {}
        try { vd?.release() } catch (_: Throwable) {}
        releaseVideoEncoderOnly()
    }

    private fun cleanup() {
        releaseVideoPipeline()
        try { activeAudioRecord?.stop() } catch (_: Throwable) {}
        try { activeAudioRecord?.release() } catch (_: Throwable) {}
        activeAudioRecord = null
        try { activeAudioCodec?.stop() } catch (_: Throwable) {}
        try { activeAudioCodec?.release() } catch (_: Throwable) {}
        activeAudioCodec = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        try { videoOut?.close() } catch (_: Throwable) {}
        try { videoSocket?.close() } catch (_: Throwable) {}
        try { audioOut?.close() } catch (_: Throwable) {}
        try { audioSocket?.close() } catch (_: Throwable) {}
        videoOut = null; videoSocket = null; audioOut = null; audioSocket = null
    }

    override fun onBind(intent: Intent?) = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("cast", "ScreenCast", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun updateNotification(text: String) {
        try { getSystemService(NotificationManager::class.java).notify(1, notification(text)) } catch (_: Throwable) {}
    }

    private fun notification(text: String): Notification =
        if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "cast")
                .setContentTitle("ScreenCast")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("ScreenCast")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }

    companion object {
        private const val TAG = "ScreenCastService"
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNELS = 2
    }
}

private fun ByteBuffer.toByteArraySafe(): ByteArray {
    val duplicate = duplicate()
    val bytes = ByteArray(duplicate.remaining())
    duplicate.get(bytes)
    return bytes
}

private fun ByteBuffer.readBytes(offset: Int, size: Int): ByteArray {
    val duplicate = duplicate()
    duplicate.position(offset)
    duplicate.limit(offset + size)
    return ByteArray(size).also { duplicate.get(it) }
}
