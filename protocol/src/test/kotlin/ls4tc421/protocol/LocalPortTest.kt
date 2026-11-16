package ls4tc421.protocol

import java.net.BindException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The local port of the client (D21): a free port by default, never 5000 unless asked. Nothing is sent. */
class LocalPortTest {
    private val loopback = InetAddress.getLoopbackAddress()

    @Test fun defaultConfigAsksForAFreePortAndKeepsRemotePort5000() {
        val defaults = ClientConfig()
        assertEquals(0, defaults.localPort)
        assertEquals(5000, defaults.remotePort)
    }

    @Test fun defaultClientDoesNotBindPort5000() {
        Tc421Client().use { client ->
            assertNotEquals(5000, client.localPort)
            assertTrue(client.localPort in 1..65535)
        }
    }

    /**
     * With port 5000 held by another program without sharing (as the original app could), a default
     * client still opens. The old default (port 5000 with `SO_REUSEADDR`) would have shared the port.
     */
    @Test fun defaultClientOpensWhilePort5000IsHeld() {
        val holder = try {
            DatagramSocket(null as java.net.SocketAddress?).apply { bind(InetSocketAddress(loopback, 5000)) }
        } catch (_: BindException) {
            null // port 5000 already taken on this machine: the situation under test exists anyway
        }
        try {
            Tc421Client(ClientConfig(localAddress = loopback)).use { client ->
                assertNotEquals(5000, client.localPort)
            }
        } finally {
            holder?.close()
        }
    }

    @Test fun fixedLocalPortIsStillHonoured() {
        val free = DatagramSocket(InetSocketAddress(loopback, 0)).use { it.localPort }
        Tc421Client(ClientConfig(localPort = free, localAddress = loopback)).use { client ->
            assertEquals(free, client.localPort)
        }
    }
}
