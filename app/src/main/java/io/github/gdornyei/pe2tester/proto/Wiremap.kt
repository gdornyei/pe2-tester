package io.github.gdornyei.pe2tester.proto

import kotlinx.serialization.Serializable

/**
 * Wiremap result. Conductors are numbered 1..8 (RJ45 pins) and 9 = shield.
 * connections[i] = remote pin the local pin i is connected to (0 = open, 255 = connected but unidentified).
 * shorts[i] = pin that local pin i is shorted to (0 = none).
 */
@Serializable
data class WiremapResult(
    val connections: IntArray, val shorts: IntArray, val remoteId: Int, val adapterType: Int,
    val summary: String, val status: Status,
)

/**
 * Port of the vendor wiremap algorithm: the device measures DC resistance between all 36 conductor pairs.
 * The remote wiremap adapter connects each wire through a specific resistor network, from which the
 * mapping is inferred.
 */
object Wiremap {
    private const val RES_MAX = 1_000_000
    private const val RES_MIN = 12_000
    private const val SHORT_MAX = 100
    private const val TOL = 0.037f
    private const val TOL_ID = 0.045f
    const val ADAPTER_UNKNOWN = 0
    const val ADAPTER_V1 = 1
    const val ADAPTER_V2 = 2
    private val SEARCH = intArrayOf(
        0, 0, 0, 100000, 270000, 420000, 100000, 370000, 520000, 150000, 270000, 370000, 180000, 300000, 520000,
        180000, 480000, 700000, 150000, 420000, 520000, 220000, 300000, 480000, 220000, 520000, 700000,
        330000, 430000, 600000,
    )
    private val ROW = intArrayOf(0, 8, 15, 21, 26, 30, 33, 35)

    private fun near(nominal: Int, tol: Float, v: Int): Boolean {
        val t = tol.toDouble()
        val hi = ((t + 1.0) * nominal).toFloat(); val lo = (nominal * (1.0 - t)).toFloat()
        val f = v.toFloat()
        return f >= lo && f <= hi
    }

    private fun res(a: IntArray, i0: Int, j0: Int, set: Int = 0): Int {
        val i = minOf(i0, j0); val j = maxOf(i0, j0)
        if (i == j || j > 9 || i == 0) return 0
        val idx = ROW[i - 1] + (j - i - 1)
        if (set > 0) a[idx] = set
        return a[idx]
    }

    private fun detectAdapter(a: IntArray): Int {
        val v1 = intArrayOf(22000, 18000, 27000, 15000, 91000, 48000, 113000, 130000, 148000)
        val v2 = intArrayOf(100000, 150000, 180000, 220000, 270000, 300000, 330000, 370000, 420000, 430000, 480000, 520000, 600000, 700000)
        var m1 = 0; var m2 = 0; var low = 0
        for (r in a) {
            if (r > RES_MIN && r < RES_MAX) {
                if (v1.any { near(it, TOL, r) }) { m1++; if (r < 100000) low++ }
                if (v2.any { near(it, TOL, r) }) m2++
            }
        }
        return when {
            low > 0 -> ADAPTER_V1
            m1 == 0 && m2 == 0 -> ADAPTER_UNKNOWN
            m2 > m1 -> ADAPTER_V2
            else -> ADAPTER_V1
        }
    }

    private fun matchV1(r: Int, rs1: Int, rs2: Int): Int {
        val (w1, w2) = when {
            near(22000, TOL, r) -> 1 to 2
            near(18000, TOL, r) -> 3 to 6
            near(27000, TOL, r) -> 4 to 5
            near(15000, TOL, r) -> 7 to 8
            near(91000, TOL, r) -> 9 to 2
            near(48000, TOL, r) -> 7 to 5
            near(113000, TOL, r) -> 9 to 1
            near(130000, TOL, r) -> 9 to 6
            near(148000, TOL, r) -> 9 to 3
            else -> return 0
        }
        return if (rs1 == rs2) w1 * 10 + w2 else if (rs1 > rs2) w1 else w2
    }

    private fun idFromRes(r: Int): Int {
        val ids = intArrayOf(169000, 187000, 205000, 226000, 249000, 274000, 301000, 332000)
        for (k in ids.indices) if (near(ids[k], TOL_ID, r)) return k + 1
        return -1
    }

    private fun detectV2(a: IntArray, conn: IntArray, shorts: IntArray) {
        for (i in 1..9) if (shorts[i] != 0) return
        fun pass(c1: Int, c2: Int) {
            for (i in 1..9) {
                if (conn[i] != 0) continue
                jl@ for (j in 1..9) {
                    val t1 = SEARCH[j * 3 + c1]; val t2 = SEARCH[j * 3 + c2]
                    if (t1 == 0 && t2 == 0) continue
                    var m1 = 0; var m2 = 0
                    for (k in 1..8) {
                        if (k == i) continue
                        val r = res(a, i, k)
                        if (near(t1, TOL, r)) m1 = k
                        if (near(t2, TOL, r)) m2 = k
                        if (m1 != 0 && m2 != 0) { conn[i] = j; break@jl }
                    }
                }
            }
        }
        pass(0, 1); pass(0, 2); pass(1, 2)
    }

    fun parse(p: Packet): WiremapResult? {
        if (p.payload.size < 144) return null
        val a = IntArray(36) { p.s32(it * 4) }
        val type = detectAdapter(a)
        val conn = IntArray(10); val shorts = IntArray(10); val sums = IntArray(10)
        for (i in 1..9) for (j in 1..9) {
            if (i == j) continue
            val r = res(a, i, j)
            if (r in 0 until SHORT_MAX) shorts[i] = j
            if (r > RES_MIN && r < RES_MAX) sums[i] += r else res(a, i, j, SHORT_MAX)
        }
        if (type == ADAPTER_V2) {
            detectV2(a, conn, shorts)
        } else {
            for (i in 1..9) for (j in 1..9) {
                if (i == j) continue
                val c = conn[i]
                if (c != 0 && c < 10) continue
                val r = res(a, i, j)
                if (r < RES_MIN) continue
                val m = matchV1(r, sums[i], sums[j])
                if (m > 0) conn[i] = m
            }
        }
        var id = 0
        if (type == ADAPTER_V1) {
            val p5 = conn.indexOfLast { it == 5 }.takeIf { it > 0 } ?: 5
            val p6 = conn.indexOfLast { it == 6 }.takeIf { it > 0 } ?: 6
            id = idFromRes(res(a, p5, p6))
        }
        for (i in 1..9) {
            if (conn[i] != 0) continue
            for (j in 1..9) {
                if (i == j) continue
                val r = res(a, i, j)
                if (r > RES_MIN && r < RES_MAX) { conn[i] = 255; break }
            }
        }
        val (summary, st) = summarize(conn, shorts)
        return WiremapResult(conn, shorts, id, type, summary, st)
    }

    private fun summarize(c: IntArray, s: IntArray): Pair<String, Status> {
        var any = false
        for (i in 1..9) {
            if (s[i] != 0) return "Short" to Status.FAIL
            if (c[i] != 0) any = true
        }
        if (!any) return "Open cable / no wiremap adapter" to Status.FAIL
        if ((1..8).all { c[it] == 9 - it }) return (if (c[9] == 9) "Rollover (shielded)" else "Rollover") to Status.OK
        var ab = if (c[1] == 1 && c[2] == 2 && c[3] == 3 && c[6] == 6) 1 else 0
        if (c[1] == 3 && c[2] == 6 && c[3] == 1 && c[6] == 2) ab = 2
        var cd = if (c[4] == 4 && c[5] == 5 && c[7] == 7 && c[8] == 8) 1 else 0
        if (c[4] == 7 && c[5] == 8 && c[7] == 4 && c[8] == 5) cd = 2
        if (c[4] == 0 && c[5] == 0 && c[7] == 0 && c[8] == 0) cd = 3
        var st = Status.OK
        var txt = when {
            cd == 3 && ab == 1 -> "2-pair straight"
            cd == 3 && ab == 2 -> "2-pair crossover"
            cd == 1 && ab == 1 -> "4-pair straight"
            cd == 1 && ab == 2 -> "4-pair mixed crossover"
            cd == 2 && ab == 2 -> "4-pair full crossover"
            else -> { st = Status.FAIL; "Miswire" }
        }
        if (c[9] == 9) txt += " (shielded)"
        return txt to st
    }
}
