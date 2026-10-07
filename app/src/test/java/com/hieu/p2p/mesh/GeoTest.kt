package com.hieu.p2p.mesh

import com.hieu.p2p.mesh.core.GeoPoint
import com.hieu.p2p.mesh.core.NodeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    // Hoan Kiem lake area, Hanoi.
    private val turtleTower = GeoPoint.of(21.028511, 105.852350, 5f, 0)
    private val opera = GeoPoint.of(21.024280, 105.857580, 5f, 0)

    @Test
    fun distanceMatchesKnownValue() {
        // ~720 m between the two landmarks.
        val d = turtleTower.distanceTo(opera)
        assertTrue("got $d", d in 700.0..740.0)
        assertEquals(0.0, turtleTower.distanceTo(turtleTower), 0.01)
    }

    @Test
    fun shortDistancesAreAccurateToCentimetres() {
        // 0.0001 deg latitude is ~11.1 m anywhere.
        val a = GeoPoint.of(10.0, 106.0, 3f, 0)
        val b = GeoPoint.of(10.0001, 106.0, 3f, 0)
        assertEquals(11.12, a.distanceTo(b), 0.05)
    }

    @Test
    fun bearingPointsTheRightWay() {
        val origin = GeoPoint.of(21.0, 105.8, 5f, 0)
        assertEquals(0.0, origin.bearingTo(GeoPoint.of(21.001, 105.8, 5f, 0)), 0.5)   // north
        assertEquals(90.0, origin.bearingTo(GeoPoint.of(21.0, 105.801, 5f, 0)), 0.5)  // east
        assertEquals(180.0, origin.bearingTo(GeoPoint.of(20.999, 105.8, 5f, 0)), 0.5) // south
        assertEquals(270.0, origin.bearingTo(GeoPoint.of(21.0, 105.799, 5f, 0)), 0.5) // west
        assertEquals(135.0, turtleTower.bearingTo(opera), 15.0)                         // south-east
    }

    @Test
    fun directionIsOnlyTrustedBeyondGpsError() {
        // Two phones on one desk, indoors: ~26 m accuracy each (as measured on a Galaxy A17).
        val a = GeoPoint.of(21.035973, 105.815944, 26.5f, 0)
        val b = GeoPoint.of(21.036020, 105.815900, 26.5f, 0) // ~7 m away
        assertTrue(a.distanceTo(b) < 10)
        assertTrue(!a.directionReliableTo(b))
        // Same accuracy, 150 m apart: direction is meaningful.
        val far = GeoPoint.of(21.037320, 105.815944, 26.5f, 0)
        assertTrue(a.directionReliableTo(far))
        // Excellent fixes still need a few metres of separation.
        val p = GeoPoint.of(10.0, 106.0, 1f, 0)
        val q = GeoPoint.of(10.00003, 106.0, 1f, 0) // ~3.3 m
        assertTrue(!p.directionReliableTo(q))
    }

    @Test
    fun positionAndBatteryCrossTheMesh() {
        val net = FakeNetwork()
        listOf("A", "B", "C").forEach { net.node(it) }
        net.link("A", "B"); net.link("B", "C")
        val a = net.nodes.getValue("A")
        val c = net.nodes.getValue("C")
        a.setLocalStatus(NodeStatus(GeoPoint.of(21.028511, 105.852350, 6f, net.now - 3_000), 64))
        net.advance(15_000)

        val seen = c.status.value.routes.single { it.dest == a.id }
        assertEquals(2, seen.hops)
        assertEquals(64, seen.batteryPercent)
        val pos = seen.position!!
        assertEquals(21.028511, pos.lat, 1e-6)
        assertEquals(105.852350, pos.lon, 1e-6)
        assertEquals(6, pos.accuracyM)
        assertTrue("fix should be ~3-18 s old", net.now - pos.fixedAt in 3_000..19_000)

        // Turning sharing off clears it everywhere.
        a.setLocalStatus(NodeStatus(null, 63))
        net.advance(15_000)
        assertNull(c.status.value.routes.single { it.dest == a.id }.position)
    }

    @Test
    fun movingFarAnnouncesEarly() {
        val net = FakeNetwork()
        listOf("A", "B").forEach { net.node(it) }
        net.link("A", "B")
        net.advance(15_000)
        val a = net.nodes.getValue("A")
        val b = net.nodes.getValue("B")
        a.setLocalStatus(NodeStatus(GeoPoint.of(21.0, 105.8, 5f, net.now)))
        net.drain()
        net.advance(1_000)
        val first = b.status.value.routes.single().position!!
        // Walk 100 m north: B learns within a second, not at the next 10 s announce.
        a.setLocalStatus(NodeStatus(GeoPoint.of(21.0009, 105.8, 5f, net.now)))
        net.drain(); net.advance(1_000)
        val second = b.status.value.routes.single().position!!
        assertTrue(first.distanceTo(second) > 90)
    }
}
