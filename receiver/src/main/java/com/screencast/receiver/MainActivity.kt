package com.screencast.receiver

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private val statusHandler = Handler(Looper.getMainLooper())
    private var hideStatusRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        surface = findViewById(R.id.surface)
        status = findViewById(R.id.status)

        // Uygulama ilk açıldığında status yazısını gizle
        status.visibility = View.GONE
        status.text = ""

        server = ReceiverServer(

            status = { msg ->
                showStatus(msg)
            },

            onVideoFormat = { width, height ->
                runOnUiThread {
                    fitSurfaceToVideo(width, height)
                }
            }
        )

        server!!.start(surface)
    }

    private fun showStatus(message: String) {

        runOnUiThread {

            // Önceki timer varsa iptal et
            hideStatusRunnable?.let {
                statusHandler.removeCallbacks(it)
            }

            // Yeni mesajı göster
            status.text = message
            status.visibility = View.VISIBLE

            // 10 saniye sonra gizle
            hideStatusRunnable = Runnable {
                status.visibility = View.GONE
                status.text = ""
            }

            statusHandler.postDelayed(
                hideStatusRunnable!!,
                10_000L
            )
        }
    }

    private fun fitSurfaceToVideo(
        videoWidth: Int,
        videoHeight: Int
    ) {
        root.post {

            val cw = root.width
            val ch = root.height

            if (
                cw <= 0 ||
                ch <= 0 ||
                videoWidth <= 0 ||
                videoHeight <= 0
            ) {
                return@post
            }

            val scale = minOf(
                cw.toFloat() / videoWidth,
                ch.toFloat() / videoHeight
            )

            surface.layoutParams =
                FrameLayout.LayoutParams(
                    (videoWidth * scale)
                        .toInt()
                        .coerceAtLeast(1),

                    (videoHeight * scale)
                        .toInt()
                        .coerceAtLeast(1),

                    Gravity.CENTER
                )
        }
    }

    override fun onDestroy() {

        hideStatusRunnable?.let {
            statusHandler.removeCallbacks(it)
        }

        server?.stop()

        super.onDestroy()
    }
}


class ReceiverServer(
    private val status: (String) -> Unit,
    private val onVideoFormat: (Int, Int) -> Unit
) {

    @Volatile
    private var running = true

    private var udp: DatagramSocket? = null
    private var videoServer: ServerSocket? = null
    private var audioServer: ServerSocket? = null

    fun start(surface: SurfaceView) {

        Thread({

            try {

                udp = DatagramSocket(
                    Protocol.DISCOVERY_PORT
                ).apply {
                    broadcast = true
                }

                Thread(
                    {
                        discoveryLoop()
                    },
                    "DiscoveryResponder"
                ).start()


                videoServer =
                    ServerSocket(
                        Protocol.VIDEO_PORT
                    )

                audioServer =
                    ServerSocket(
                        Protocol.AUDIO_PORT
                    )


                status(
                    "ScreenCast Receiver hazır\nTelefon bağlantısı bekleniyor..."
                )


                // AUDIO SERVER
                Thread({

                    while (running) {

                        try {

                            val socket =
                                audioServer!!.accept()

                            status(
                                "Ses bağlantısı kuruldu"
                            )

                            Thread(
                                {
                                    AudioReceiver(
                                        socket,
                                        status
                                    ).run()
                                },
                                "AudioReceiver"
                            ).start()

                        } catch (e: Throwable) {

                            if (!running)
                                break
                        }
                    }

                }, "AudioAccept").start()


                // VIDEO SERVER
                while (running) {

                    try {

                        val socket =
                            videoServer!!.accept()

                        status(
                            "Telefon bağlandı: ${socket.inetAddress.hostAddress}"
                        )

                        Thread(
                            {
                                H264Receiver(
                                    socket,
                                    surface.holder.surface,
                                    status,
                                    onVideoFormat
                                ).run()
                            },
                            "VideoReceiver"
                        ).start()

                    } catch (e: Throwable) {

                        if (!running)
                            break
                    }
                }


            } catch (e: Throwable) {

                if (running) {

                    status(
                        "Receiver hatası: ${e.message}"
                    )
                }

            } finally {

                try {
                    udp?.close()
                } catch (_: Throwable) {
                }

                try {
                    videoServer?.close()
                } catch (_: Throwable) {
                }

                try {
                    audioServer?.close()
                } catch (_: Throwable) {
                }
            }

        }, "ReceiverServer").start()
    }


    private fun discoveryLoop() {

        val buf =
            ByteArray(256)

        while (running) {

            try {

                val packet =
                    DatagramPacket(
                        buf,
                        buf.size
                    )

                udp?.receive(packet)

                val text =
                    String(
                        packet.data,
                        0,
                        packet.length
                    )

                if (
                    text ==
                    Protocol.DISCOVERY
                ) {

                    val response =
                        (
                            Protocol.RESPONSE_PREFIX +
                            "Android TV / TV Box"
                        ).toByteArray()

                    udp?.send(
                        DatagramPacket(
                            response,
                            response.size,
                            packet.address,
                            packet.port
                        )
                    )
                }

            } catch (e: Throwable) {

                if (!running)
                    break
            }
        }
    }


    fun stop() {

        running = false

        try {
            udp?.close()
        } catch (_: Throwable) {
        }

        try {
            videoServer?.close()
        } catch (_: Throwable) {
        }

        try {
            audioServer?.close()
        } catch (_: Throwable) {
        }
    }
}
