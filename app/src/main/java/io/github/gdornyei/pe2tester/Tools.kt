package io.github.gdornyei.pe2tester

import io.github.gdornyei.pe2tester.ble.ConnState
import io.github.gdornyei.pe2tester.ble.Pe2Ble
import io.github.gdornyei.pe2tester.proto.Cmd
import io.github.gdornyei.pe2tester.proto.LinkParser
import io.github.gdornyei.pe2tester.proto.LinkResult
import io.github.gdornyei.pe2tester.proto.PT
import io.github.gdornyei.pe2tester.proto.Packet
import io.github.gdornyei.pe2tester.proto.Tdr
import io.github.gdornyei.pe2tester.proto.TdrResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class ToolsUi(
    val toner: Boolean = false, val tonerPair: Int = 15, val tonerShield: Boolean = false, val tonerTone: Int = 1, val tonerLoud: Boolean = true,
    val link: Int = 0, // 0 off, 1 on, 2 blink
    val linkSpeed: Int = 0, val linkDuplex: Int = 3, val linkMdi: Int = 3, val linkInfo: LinkResult? = null,
    val tdrBusy: Boolean = false, val tdr: TdrResult? = null, val xtalk: List<List<Int>> = emptyList(),
    val calLen: String = "", val calBusy: Boolean = false, val calNvp: Int? = null, val calInfo: String? = null,
    val hunt: Boolean = false, val huntState: HuntState = HuntState.IDLE, val huntText: String = "", val huntCount: Int = 0,
    val huntRemoteId: Int = 0, val huntFoundAt: Long = 0,
    val busy: Boolean = false, val error: String? = null,
)

enum class HuntState { IDLE, NOTHING, ACTIVE_PORT, SHORT, FOUND }

/** Toner, port blinker and TDR graph – the vendor app's "Tools" tab. */
class Tools(private val ble: Pe2Ble, private val scope: CoroutineScope) {
    private val _ui = MutableStateFlow(ToolsUi())
    val ui: StateFlow<ToolsUi> = _ui
    private var job: Job? = null
    private var linkWatch: Job? = null

    fun update(f: (ToolsUi) -> ToolsUi) = _ui.update(f)

    private suspend fun ensure() { if (ble.state.value != ConnState.CONNECTED) ble.connect() }

    private suspend fun request(p: Packet, expect: Int, timeoutMs: Long): Packet? = coroutineScope {
        val w = async(start = CoroutineStart.UNDISPATCHED) { ble.packets.first { it.ptype == expect || it.ptype == PT.NACK } }
        ble.send(p)
        val r = withTimeoutOrNull(timeoutMs) { w.await() }
        w.cancel()
        r?.takeIf { it.ptype == expect }
    }

    private fun launchTool(block: suspend () -> Unit) {
        if (huntJob?.isActive == true) stopHunt()
        job?.cancel()
        job = scope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try { ensure(); block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                _ui.update { it.copy(error = e.message) }
            } finally { _ui.update { it.copy(busy = false) } }
        }
    }

    // ---------------------------------------------------------------- toner

    fun setToner(on: Boolean) = launchTool {
        val u = _ui.value
        if (u.link != 0) stopLinkInternal()
        if (on) ble.send(Cmd.toner(u.tonerPair, u.tonerShield, u.tonerTone, u.tonerLoud)) else ble.send(Cmd.tonerOff())
        _ui.update { it.copy(toner = on) }
    }

    /** Re-sends toner settings if it is running (after changing pair / tone / volume). */
    fun tonerChanged() { if (_ui.value.toner) setToner(true) }

    // ---------------------------------------------------------------- port blinker / manual link

    fun setLink(mode: Int) = launchTool {
        val u = _ui.value
        if (u.toner) { ble.send(Cmd.tonerOff()); _ui.update { it.copy(toner = false) } }
        if (mode == 0) { stopLinkInternal(); return@launchTool }
        _ui.update { it.copy(link = mode, linkInfo = null) }
        request(Cmd.manualLink(u.linkSpeed, u.linkDuplex, u.linkMdi, blink = mode == 2), PT.ACK, 3000)
        linkWatch?.cancel()
        linkWatch = scope.launch {
            ble.packets.collect { p -> if (p.ptype == PT.PHY_LINKINFO) _ui.update { it.copy(linkInfo = LinkParser.parse(p)) } }
        }
    }

    private suspend fun stopLinkInternal() {
        linkWatch?.cancel(); linkWatch = null
        runCatching { ble.send(Cmd.ethStop()) }
        _ui.update { it.copy(link = 0, linkInfo = null) }
    }

    // ---------------------------------------------------------------- TDR graph incl. crosstalk

    fun runTdrGraph(nvp: Int) = launchTool {
        val u = _ui.value
        if (u.link != 0) stopLinkInternal()
        if (u.toner) { ble.send(Cmd.tonerOff()); _ui.update { it.copy(toner = false) } }
        _ui.update { it.copy(tdrBusy = true, tdr = null, xtalk = emptyList()) }
        try {
            request(Cmd.ifUpForTdr(), PT.ACK, 3000)
            val t = request(Cmd.tdr4Pair(), PT.TDR_4PAIR_SAME, 6000)?.let { Tdr.parse(it, nvp) }
            _ui.update { it.copy(tdr = t) }
            val x = request(Cmd.tdrCross(), PT.TDR_4PAIR_CROSS, 9000)?.let { p ->
                val all = Tdr.decompress(p.payload); val n = all.size / 6
                (0 until 6).map { all.subList(it * n, it * n + n).toList() }
            } ?: emptyList()
            _ui.update { it.copy(xtalk = x) }
            if (t == null) _ui.update { it.copy(error = "No TDR response") }
        } finally {
            runCatching { ble.send(Cmd.ethStop()) }
            _ui.update { it.copy(tdrBusy = false) }
        }
    }

    /** Measures an open-ended cable of known length and derives the NVP. */
    fun calibrate() = launchTool {
        val len = _ui.value.calLen.replace(',', '.').toFloatOrNull()
        if (len == null || len < 5f || len > 300f) { _ui.update { it.copy(calInfo = "Enter a length between 5 and 300 m") }; return@launchTool }
        _ui.update { it.copy(calBusy = true, calNvp = null, calInfo = null) }
        try {
            request(Cmd.ifUpForTdr(), PT.ACK, 3000)
            val t = request(Cmd.tdr4Pair(), PT.TDR_4PAIR_SAME, 6000)?.let { Tdr.parse(it, 71) }
            val ends = t?.pairs?.filter { it.status == "Open" || it.status == "Short" }?.map { it.distanceM }.orEmpty()
            if (ends.isEmpty()) {
                _ui.update { it.copy(calInfo = "No cable end detected. The far end must be free (open), not plugged into a device.") }
            } else {
                val d71 = ends.average().toFloat()
                val nvp = (71 + 100 * (len / d71 - 1)).let { Math.round(it) }.coerceIn(50, 99)
                _ui.update { it.copy(calNvp = nvp, calInfo = "Measured (at NVP 71): %.1f m → suggested NVP: %d%%".format(d71, nvp)) }
            }
        } finally {
            runCatching { ble.send(Cmd.ethStop()) }
            _ui.update { it.copy(calBusy = false) }
        }
    }

    // ---------------------------------------------------------------- adapter finder (continuous wiremap)

    private var huntJob: Job? = null
    /** Called on the transition to FOUND (UI: vibrate + beep). */
    var onAdapterFound: (() -> Unit)? = null

    fun startHunt() {
        if (huntJob?.isActive == true) return
        huntJob = scope.launch {
            try {
                ensure()
                if (_ui.value.toner) { ble.send(Cmd.tonerOff()); _ui.update { it.copy(toner = false) } }
                if (_ui.value.link != 0) stopLinkInternal()
                _ui.update { it.copy(hunt = true, huntState = HuntState.IDLE, huntText = "Searching…", huntCount = 0, error = null) }
                var last = HuntState.IDLE
                while (true) {
                    val p = request(Cmd.wiremap(), PT.MEAS_REDC, 5000)
                    val w = p?.let { io.github.gdornyei.pe2tester.proto.Wiremap.parse(it) }
                    val st = when {
                        w == null -> HuntState.NOTHING
                        io.github.gdornyei.pe2tester.data.Limits.isTransformerPattern(w.shorts) -> HuntState.ACTIVE_PORT
                        w.shorts.any { it != 0 } -> HuntState.SHORT
                        w.connections.any { it != 0 } -> HuntState.FOUND
                        else -> HuntState.NOTHING
                    }
                    val txt = when (st) {
                        HuntState.FOUND -> "Adapter found! ${w?.summary ?: ""}"
                        HuntState.ACTIVE_PORT -> "Active switch/device port – not the cable end you are looking for"
                        HuntState.SHORT -> "Short on the cable (${w?.summary ?: ""})"
                        else -> "No adapter on this cable"
                    }
                    if (st == HuntState.FOUND && last != HuntState.FOUND) onAdapterFound?.invoke()
                    _ui.update { it.copy(huntState = st, huntText = txt, huntCount = it.huntCount + 1, huntRemoteId = w?.remoteId ?: 0,
                        huntFoundAt = if (st == HuntState.FOUND && last != HuntState.FOUND) System.currentTimeMillis() else it.huntFoundAt) }
                    last = st
                    kotlinx.coroutines.delay(300)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                _ui.update { it.copy(error = e.message) }
            } finally {
                _ui.update { it.copy(hunt = false) }
            }
        }
    }

    fun stopHunt() { huntJob?.cancel(); huntJob = null; _ui.update { it.copy(hunt = false, huntState = HuntState.IDLE, huntText = "") } }

    fun stopAll() {
        stopHunt()
        job?.cancel()
        val u = _ui.value
        scope.launch {
            if (u.toner) runCatching { ble.send(Cmd.tonerOff()) }
            if (u.link != 0) { linkWatch?.cancel(); runCatching { ble.send(Cmd.ethStop()) } }
            _ui.update { it.copy(toner = false, link = 0, linkInfo = null, busy = false, tdrBusy = false) }
        }
    }
}
