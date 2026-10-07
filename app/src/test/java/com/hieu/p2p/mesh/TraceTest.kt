package com.hieu.p2p.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceTest {

    @Test
    fun traceListsEveryRelayInOrderThenTheTarget() {
        val net = FakeNetwork()
        listOf("A", "B", "C", "D").forEach { net.node(it) }
        net.link("A", "B"); net.link("B", "C"); net.link("C", "D")
        net.advance(15_000)
        val a = net.nodes.getValue("A")
        val d = net.nodes.getValue("D")

        a.trace(d.id)
        net.drain()

        val t = a.trace.value!!
        assertTrue(t.reached)
        assertTrue(t.done)
        assertEquals(listOf(1, 2, 3), t.hops.map { it.index })
        assertEquals(
            listOf(net.nodes.getValue("B").id, net.nodes.getValue("C").id, d.id),
            t.hops.map { it.node },
        )
    }

    @Test
    fun traceFollowsTheRerouteAfterALinkBreaks() {
        // Ring A-B-C-D-A: A reaches B directly, then via D and C once A-B breaks.
        val net = FakeNetwork()
        listOf("A", "B", "C", "D").forEach { net.node(it) }
        net.link("A", "B"); net.link("B", "C"); net.link("C", "D"); net.link("D", "A")
        net.advance(15_000)
        val a = net.nodes.getValue("A")
        val b = net.nodes.getValue("B")

        a.trace(b.id)
        net.drain()
        assertEquals(listOf(b.id), a.trace.value!!.hops.map { it.node })

        net.unlink("A", "B")
        net.advance(15_000)
        a.trace(b.id)
        net.drain()
        assertEquals(
            listOf(net.nodes.getValue("D").id, net.nodes.getValue("C").id, b.id),
            a.trace.value!!.hops.map { it.node },
        )
    }

    @Test
    fun traceWithoutRouteSaysSoAndSilentTargetTimesOut() {
        val net = FakeNetwork()
        listOf("A", "B", "C").forEach { net.node(it) }
        net.link("A", "B"); net.link("B", "C")
        net.advance(15_000)
        val a = net.nodes.getValue("A")
        val c = net.nodes.getValue("C")

        // Drop C's answers: the relay still reports, the trace ends unreached after the timeout.
        net.interceptor = { from, _, bytes -> if (from == "C") null else bytes }
        a.trace(c.id)
        net.drain()
        assertFalse(a.trace.value!!.done)
        assertEquals(listOf(1), a.trace.value!!.hops.map { it.index })
        net.advance(16_000)
        assertTrue(a.trace.value!!.done)
        assertFalse(a.trace.value!!.reached)

        net.interceptor = null
        net.unlink("B", "C")
        net.advance(60_000)
        a.trace(c.id)
        net.drain()
        assertTrue(a.trace.value!!.noRoute)
    }
}
