package io.github.gdornyei.pe2tester.proto

/**
 * Pockethernet 2 wire protocol framing.
 *
 * Frame on the BLE byte stream:  0x00 | COBS( ptype:u16le | crc:u16le | payload ) | 0x00
 * CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) over the unstuffed frame with the crc field zeroed.
 * The stream is split into (MTU-3) sized chunks and written to the write characteristic;
 * responses arrive as notifications on the read characteristic and are fed byte by byte.
 */
class Packet(val ptype: Int, val payload: ByteArray = ByteArray(0)) {
    fun u8(off: Int): Int = payload[off].toInt() and 0xFF
    fun s16(off: Int): Int = ((payload[off].toInt() and 0xFF) or (payload[off + 1].toInt() shl 8)).toShort().toInt()
    fun u16(off: Int): Int = (payload[off].toInt() and 0xFF) or ((payload[off + 1].toInt() and 0xFF) shl 8)
    fun s32(off: Int): Int = (payload[off].toInt() and 0xFF) or
        ((payload[off + 1].toInt() and 0xFF) shl 8) or
        ((payload[off + 2].toInt() and 0xFF) shl 16) or
        ((payload[off + 3].toInt() and 0xFF) shl 24)

    override fun toString(): String = "Packet(0x%04X, %d bytes)".format(ptype, payload.size)
}

object Crc16 {
    fun ccittFalse(data: ByteArray, len: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}

object Cobs {
    fun encode(src: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(src.size + src.size / 254 + 2)
        var codeIdx = 0
        val buf = ByteArray(src.size + src.size / 254 + 2)
        var w = 1
        var code = 1
        for (b in src) {
            if (b.toInt() == 0) {
                buf[codeIdx] = code.toByte(); codeIdx = w++; code = 1
            } else {
                buf[w++] = b; code++
                if (code == 0xFF) { buf[codeIdx] = code.toByte(); codeIdx = w++; code = 1 }
            }
        }
        buf[codeIdx] = code.toByte()
        out.write(buf, 0, w)
        return out.toByteArray()
    }

    /** Returns null on malformed input. */
    fun decode(src: ByteArray, len: Int = src.size): ByteArray? {
        val out = java.io.ByteArrayOutputStream(len)
        var i = 0
        while (i < len) {
            val code = src[i].toInt() and 0xFF
            if (code == 0) return null
            i++
            for (k in 1 until code) {
                if (i >= len) return null
                out.write(src[i].toInt()); i++
            }
            if (code < 0xFF && i < len) out.write(0)
        }
        return out.toByteArray()
    }
}

object Framing {
    fun encode(p: Packet): ByteArray {
        val raw = ByteArray(4 + p.payload.size)
        raw[0] = (p.ptype and 0xFF).toByte(); raw[1] = (p.ptype shr 8).toByte()
        System.arraycopy(p.payload, 0, raw, 4, p.payload.size)
        val crc = Crc16.ccittFalse(raw)
        raw[2] = (crc and 0xFF).toByte(); raw[3] = (crc shr 8).toByte()
        val st = Cobs.encode(raw)
        val out = ByteArray(st.size + 2)
        System.arraycopy(st, 0, out, 1, st.size)
        return out
    }

    /** Decodes one stuffed frame (without delimiters). Returns null if invalid / bad CRC. */
    fun decodeStuffed(stuffed: ByteArray, len: Int): Packet? {
        val raw = Cobs.decode(stuffed, len) ?: return null
        if (raw.size < 4) return null
        val ptype = (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8)
        val crc = (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8)
        raw[2] = 0; raw[3] = 0
        if (Crc16.ccittFalse(raw) != crc) return null
        return Packet(ptype, raw.copyOfRange(4, raw.size))
    }
}

/** Feeds notification bytes, emits complete packets. */
class FrameDecoder(private val onPacket: (Packet) -> Unit, private val onError: (String) -> Unit = {}) {
    private val buf = ByteArray(8192)
    private var n = 0

    fun feed(data: ByteArray) {
        for (b in data) {
            if (b.toInt() == 0) {
                if (n > 0) {
                    val p = Framing.decodeStuffed(buf, n)
                    if (p != null) onPacket(p) else onError("invalid frame (${n} bytes)")
                }
                n = 0
            } else if (n < buf.size) {
                buf[n++] = b
            } else {
                onError("oversized frame"); n = 0
            }
        }
    }

    fun reset() { n = 0 }
}
