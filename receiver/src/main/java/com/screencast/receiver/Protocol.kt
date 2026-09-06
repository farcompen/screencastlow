package com.screencast.receiver

import java.io.DataInputStream
import java.io.DataOutputStream

object Protocol {
    const val DISCOVERY_PORT = 37020
    const val VIDEO_PORT = 37021
    const val AUDIO_PORT = 37022
    const val DISCOVERY = "SCREENCAST_DISCOVER_V5"
    const val RESPONSE_PREFIX = "SCREENCAST_RECEIVER_V5|"

    const val TYPE_CONFIG: Byte = 0
    const val TYPE_FRAME: Byte = 1

    fun writePacket(out: DataOutputStream, type: Byte, data: ByteArray) {
        require(data.size <= 8_000_000) { "Packet too large" }
        out.writeInt(data.size + 1)
        out.writeByte(type.toInt())
        out.write(data)
        out.flush()
    }

    fun readPacket(input: DataInputStream): Pair<Byte, ByteArray> {
        val size = input.readInt()
        require(size in 1..8_000_000) { "Invalid packet size: $size" }
        val type = input.readByte()
        val data = ByteArray(size - 1)
        input.readFully(data)
        return type to data
    }
}
