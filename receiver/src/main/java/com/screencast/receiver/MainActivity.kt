package com.screencast.receiver

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private var server: ReceiverServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        surface = findViewById(R.id.surface)
        status = findViewById(R.id.status)

        server = ReceiverServer(
            status = { msg ->
                runOnUiThread {
                    if (msg == "Yayın başladı") status.visibility = View.GONE
                    else { status.visibility = View.VISIBLE; status.text = msg }
                }
            },
            onVideoFormat = { width, height -> runOnUiThread { fitSurfaceToVideo(width, height) } }
        )
        server!!.start(surface)
    }

    private fun fitSurfaceToVideo(videoWidth: Int, videoHeight: Int) {
        root.post {
            val cw = root.width; val ch = root.height
            if (cw <= 0 || ch <= 0 || videoWidth <= 0 || videoHeight <= 0) return@post
            val scale = minOf(cw.toFloat() / videoWidth, ch.toFloat() / videoHeight)
            surface.layoutParams = FrameLayout.LayoutParams(
                (videoWidth * scale).toInt().coerceAtLeast(1),
                (videoHeight * scale).toInt().coerceAtLeast(1),
                Gravity.CENTER
            )
        }
    }

    override fun onDestroy() { server?.stop(); super.onDestroy() }
}

class ReceiverServer(
    private val status: (String) -> Unit,
    private val onVideoFormat: (Int, Int) -> Unit
) {
    @Volatile private var running = true
    private var udp: DatagramSocket? = null
    private var videoServer: ServerSocket? = null
    private var audioServer: ServerSocket? = null

    fun start(surface: SurfaceView) {
        Thread({
            try {
                udp = DatagramSocket(Protocol.DISCOVERY_PORT).apply { broadcast = true }
                Thread({ discoveryLoop() }, "DiscoveryResponder").start()

                videoServer = ServerSocket(Protocol.VIDEO_PORT)
                audioServer = ServerSocket(Protocol.AUDIO_PORT)
                //status("ScreenCast Receiver hazır\nTelefon bağlantısı bekleniyor...")

                Thread({
                    while (running) {
                        try {
                            val socket = audioServer!!.accept()
                            Thread({ AudioReceiver(socket, status).run() }, "AudioReceiver").start()
                        } catch (_: Throwable) { if (!running) break }
                    }
                }, "AudioAccept").start()

                while (running) {
                    val socket = videoServer!!.accept()
                    //status("Telefon bağlandı: ${socket.inetAddress.hostAddress}")
                    Thread({ H264Receiver(socket, surface.holder.surface, status, onVideoFormat).run() }, "VideoReceiver").start()
                }
            } catch (e: Throwable) {
                if (running) status("Receiver hatası: ${e.message}")
            } finally {
                try { udp?.close() } catch (_: Throwable) {}
                try { videoServer?.close() } catch (_: Throwable) {}
                try { audioServer?.close() } catch (_: Throwable) {}
            }
        }, "ReceiverServer").start()
    }

    private fun discoveryLoop() {
        val buf = ByteArray(256)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                udp?.receive(packet)
                val text = String(packet.data, 0, packet.length)
                if (text == Protocol.DISCOVERY) {
                    val response = (Protocol.RESPONSE_PREFIX + "Android TV / TV Box").toByteArray()
                    udp?.send(DatagramPacket(response, response.size, packet.address, packet.port))
                }
            } catch (_: Throwable) { if (!running) break }
        }
    }

    fun stop() {
        running = false
        try { udp?.close() } catch (_: Throwable) {}
        try { videoServer?.close() } catch (_: Throwable) {}
        try { audioServer?.close() } catch (_: Throwable) {}
    }
}
