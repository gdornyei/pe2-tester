package io.github.gdornyei.pe2tester

import io.github.gdornyei.pe2tester.data.Measurement
import io.github.gdornyei.pe2tester.data.NamedItem
import io.github.gdornyei.pe2tester.data.Project
import io.github.gdornyei.pe2tester.data.Store
import io.github.gdornyei.pe2tester.proto.Status
import io.github.gdornyei.pe2tester.proto.TdrPair
import io.github.gdornyei.pe2tester.proto.TdrResult
import io.github.gdornyei.pe2tester.proto.WiremapResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class StoreTest {
    @Test fun nextLabel() {
        assertEquals("AP-13", Store.nextLabel("AP-12", 5))
        assertEquals("Floor2 / A-08", Store.nextLabel("Floor2 / A-07", 1))
        assertEquals("Rack1-P10b", Store.nextLabel("Rack1-P09b", 1))
        assertEquals("Port 4", Store.nextLabel("Reception", 3))
        assertEquals("Port 1", Store.nextLabel(null, 0))
    }

    @Test fun measurementSerializationRoundTrip() {
        val m = Measurement(
            label = "A-01", ts = 123L, device = "S/N 1",
            items = listOf(NamedItem("Link", Item(ItemState.OK, "1 Gbit", listOf("Speed" to "1 Gbit")))),
            wiremap = WiremapResult(IntArray(10) { it }, IntArray(10), 0, 2, "4-pair straight", Status.OK),
            tdr = TdrResult(listOf(TdrPair("Open", 12.3f, listOf(1, -2, 3))), "x", Status.OK, 12.3f),
        )
        val p = Project(name = "Test", measurements = listOf(m))
        val json = Json { encodeDefaults = true }
        val back = json.decodeFromString<Project>(json.encodeToString(p))
        assertEquals(p.measurements[0].label, back.measurements[0].label)
        assertEquals(p.measurements[0].items, back.measurements[0].items)
        assertEquals(p.measurements[0].tdr, back.measurements[0].tdr)
        assertEquals(p.measurements[0].wiremap!!.connections.toList(), back.measurements[0].wiremap!!.connections.toList())
    }
}

class NaturalSortTest {
    @org.junit.Test fun natural() {
        val l = listOf("Port 10", "Port 2", "Gi1/0/10", "Gi1/0/2", "Port 1").sortedWith(Store.natural)
        org.junit.Assert.assertEquals(listOf("Gi1/0/2", "Gi1/0/10", "Port 1", "Port 2", "Port 10"), l)
    }
}

class MergeTest {
    private fun m(id: String, ts: Long, note: String = "") = Measurement(id = id, label = id, ts = ts, items = emptyList(), note = note)

    @org.junit.Test fun unionAndTombstones() {
        val a = Project(id = "p", name = "A", updatedAt = 10, measurements = listOf(m("1", 1), m("2", 2, "old")), deletedIds = listOf("3"))
        val b = Project(id = "p", name = "B", updatedAt = 20, measurements = listOf(m("2", 5, "new"), m("3", 3), m("4", 4)))
        val r = Store.merge(a, b)
        org.junit.Assert.assertEquals("B", r.name)
        org.junit.Assert.assertEquals(listOf("1", "2", "4"), r.measurements.map { it.id }.sorted())
        org.junit.Assert.assertEquals("new", r.measurements.first { it.id == "2" }.note)
    }
}
