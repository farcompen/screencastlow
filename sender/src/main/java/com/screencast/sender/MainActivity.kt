package com.screencast.sender

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val projectionRequest = 1001
    private val audioPermissionRequest = 1002
    private var selectedIp: String? = null
    private var selectedName: String? = null
    private val found = mutableListOf<Pair<String, String>>()
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var start: Button
    private lateinit var stop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        list = findViewById(R.id.devices)
        start = findViewById(R.id.start)
        stop = findViewById(R.id.stop)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
        }

        list.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            selectedIp = found[position].first
            selectedName = found[position].second
            status.text = "Seçildi: ${found[position].second} (${found[position].first})"
        }

        start.setOnClickListener {
            if (selectedIp == null) {
                Toast.makeText(this, "Önce bir TV seçin", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requestAudioThenProjection()
        }

        stop.setOnClickListener {
            stopService(Intent(this, ScreenCastService::class.java))
            start.isEnabled = true
            stop.isEnabled = false
            status.text = "Yayın durduruldu"
        }

        discover()
    }

    private fun requestAudioThenProjection() {
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                audioPermissionRequest
            )
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), projectionRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioPermissionRequest) {
            // Ses izni reddedilse bile görüntü aktarımını başlatıyoruz.
            requestProjection()
        }
    }

    private fun discover() {
        executor.execute {
            try {
                val socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 1200
                }
                val data = Protocol.DISCOVERY.toByteArray()
                socket.send(
                    DatagramPacket(
                        data,
                        data.size,
                        InetAddress.getByName("255.255.255.255"),
                        Protocol.DISCOVERY_PORT
                    )
                )
                val buf = ByteArray(512)
                val end = System.currentTimeMillis() + 1500
                while (System.currentTimeMillis() < end) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        if (text.startsWith(Protocol.RESPONSE_PREFIX)) {
                            val name = text.removePrefix(Protocol.RESPONSE_PREFIX).ifBlank { "ScreenCast TV" }
                            val ip = packet.address.hostAddress ?: continue
                            if (found.none { it.first == ip }) found.add(ip to name)
                        }
                    } catch (_: Exception) {
                    }
                }
                socket.close()
                runOnUiThread {
                    list.adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_list_item_1,
                        found.map { "${it.second}\n${it.first}" }
                    )
                    status.text = if (found.isEmpty()) {
                        "TV bulunamadı. Aynı Wi-Fi ağında olduğunuzdan emin olun."
                    } else {
                        "${found.size} TV bulundu"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Keşif hatası: ${e.message}" }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != projectionRequest || resultCode != RESULT_OK || data == null) return

        val audioEnabled = Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        val service = Intent(this, ScreenCastService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("projectionData", data)
            putExtra("receiverIp", selectedIp)
            putExtra("audioEnabled", audioEnabled)
        }
        ContextCompat.startForegroundService(this, service)
        start.isEnabled = false
        stop.isEnabled = true
        status.text = if (audioEnabled) {
            "${selectedName ?: "TV"} görüntü + ses yayını başlatılıyor..."
        } else {
            "${selectedName ?: "TV"} görüntü yayını başlatılıyor..."
        }
    }
}
