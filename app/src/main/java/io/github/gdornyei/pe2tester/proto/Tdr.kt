package io.github.gdornyei.pe2tester.proto

import kotlin.math.abs
import kotlinx.serialization.Serializable

@Serializable
data class TdrPair(val status: String, val distanceM: Float, val samples: List<Int>)

@Serializable
data class TdrResult(val pairs: List<TdrPair>, val summary: String, val status: Status, val avgDistanceM: Float?)

/** Port of the vendor TDR evaluation (4 pairs measured against themselves). */
object Tdr {
    private const val PEAK_MAX_IMPEDANCE_MISMATCH = 15
    private const val MIN_PEAK_IDX = 32
    const val PAIR_NAMES = "1-2,3-6,4-5,7-8"

    /** Run-length / packed sample decompression. */
    fun decompress(b: ByteArray): List<Int> {
        val out = ArrayList<Int>(1024)
        for (k in 0 until b.size / 2) {
            var w = (b[2 * k].toInt() and 0xFF) or ((b[2 * k + 1].toInt() and 0xFF) shl 8)
            when {
                w and 0x8000 != 0 -> repeat(3) {
                    var v = w and 15; if (w and 16 != 0) v = -v
                    out += v; w = w shr 5
                }
                w and 0x4000 != 0 -> repeat(2) {
                    var v = w and 63; if (w and 64 != 0) v = -v
                    out += v; w = w shr 7
                }
                w and 0xFE00 == 0x3E00 -> repeat((w and 0x1FF) + 1) { out += 0 }
                else -> {
                    var v = w and 255; if (w and 256 != 0) v = -v
                    repeat(((w shr 9) and 31) + 1) { out += v }
                }
            }
        }
        return out
    }

    fun distance(idx: Int, nvp: Int = 71): Float {
        val f = ((nvp - 71) / 100.0 + 1.0).toFloat()
        val d = if (idx < 50) idx * 0.4591 - 15.0243 else idx * 0.4288 - 11.76
        return (d * f).toFloat()
    }

    private fun peaks(s: List<Int>): List<Int> {
        val out = ArrayList<Int>()
        var dir = 0; var prev = 0
        for (i in s.indices) {
            val v = s[i]
            when {
                dir == 0 -> if (v != prev) { dir = v; prev = v }
                dir < 0 -> { if (v > prev) { out += i - 1; dir = 1 }; prev = v }
                else -> { if (v < prev) { out += i - 1; dir = -1 }; prev = v }
            }
        }
        return out
    }

    fun analyzePair(s: List<Int>, nvp: Int = 71): TdrPair {
        val pk = peaks(s)
        if (pk.isEmpty()) return TdrPair("", 0f, s)
        var bestIdx = 0; var bestVal = 0; var bestPos = 0
        for (k in pk.indices.reversed()) {
            val idx = pk[k]; val v = s[idx]
            if (idx < MIN_PEAK_IDX) continue
            if (abs(v) > abs(bestVal)) { bestPos = k; bestIdx = idx; bestVal = v }
        }
        if (bestVal == 0) return TdrPair("", 0f, s)
        if (bestIdx < 50 && bestPos < pk.size - 1) {
            val next = pk[bestPos + 1]
            if (next - bestIdx < 10) bestIdx = (bestIdx + next) / 2
        }
        val d = distance(bestIdx, nvp).coerceAtLeast(0f)
        val st = when {
            abs(bestVal) < PEAK_MAX_IMPEDANCE_MISMATCH -> "Terminated"
            bestVal < 0 -> "Short"
            else -> "Open"
        }
        return TdrPair(st, Math.round(d * 10) / 10f, s)
    }

    fun parse(p: Packet, nvp: Int = 71): TdrResult {
        val all = decompress(p.payload)
        val n = all.size / 4
        val pairs = (0..3).map { analyzePair(all.subList(it * n, it * n + n), nvp) }
        var maxDiff = 0f; var same = true
        for (i in 0..3) for (j in i + 1..3) {
            maxDiff = maxOf(maxDiff, abs(pairs[i].distanceM - pairs[j].distanceM))
            if (pairs[i].status != pairs[j].status) same = false
        }
        if (maxDiff > 1.5f || !same) return TdrResult(pairs, "Mixed results", Status.FAIL, null)
        val avg = pairs.map { it.distanceM }.average().toFloat()
        val label = pairs[0].status.ifEmpty { "No reflection" }
        return TdrResult(pairs, "$label @ ${"%.1f".format(avg)} m", Status.OK, avg)
    }
}
