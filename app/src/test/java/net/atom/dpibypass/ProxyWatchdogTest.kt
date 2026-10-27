package net.atom.dpibypass

import net.atom.dpibypass.vpn.ProxyListenerProbe
import net.atom.dpibypass.vpn.ProxyWatchdogPolicy
import net.atom.dpibypass.vpn.ProxyWatchdogPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class ProxyWatchdogTest {
    @Test fun healthyEngineIsNeverRestarted() {
        val policy = ProxyWatchdogPolicy()
        repeat(100) { assertEquals(Action.None, policy.observe(true, true, it * 15_000L)) }
    }

    @Test fun deadEngineRestartsImmediately() {
        assertEquals(Action.Restart, ProxyWatchdogPolicy().observe(false, false, 0))
    }

    @Test fun briefUnresponsivenessDoesNotDropConnections() {
        val policy = ProxyWatchdogPolicy(unresponsiveLimit = 3)
        assertEquals(Action.None, policy.observe(true, false, 0))
        assertEquals(Action.None, policy.observe(true, false, 15_000))
        assertEquals(Action.None, policy.observe(true, true, 30_000))
        assertEquals(Action.None, policy.observe(true, false, 45_000))
        assertEquals(Action.None, policy.observe(true, false, 60_000))
        assertEquals(Action.Restart, policy.observe(true, false, 75_000))
    }

    @Test fun repeatedDeathsBackOffAndRecoverAfterStableRun() {
        val policy = ProxyWatchdogPolicy(minBackoffMs = 1_000, maxBackoffMs = 4_000, stableResetMs = 60_000)
        assertEquals(Action.Restart, policy.observe(false, false, 0))
        assertEquals(Action.None, policy.observe(false, false, 500))
        assertEquals(Action.Restart, policy.observe(false, false, 1_000))
        assertEquals(Action.None, policy.observe(false, false, 2_500))
        assertEquals(Action.Restart, policy.observe(false, false, 3_000))
        assertEquals(Action.None, policy.observe(false, false, 6_000))
        assertEquals(Action.Restart, policy.observe(false, false, 7_000))
        // Uzun süre sağlıklı kaldıktan sonra yeni bir ölüm beklemeden toparlanır.
        assertEquals(Action.None, policy.observe(true, true, 80_000))
        assertEquals(Action.Restart, policy.observe(false, false, 80_500))
    }

    @Test fun probeAcceptsSocksReply() {
        ServerSocket(0).use { server ->
            thread {
                server.accept().use { c ->
                    c.getInputStream().read(ByteArray(3))
                    c.getOutputStream().write(byteArrayOf(0x05, 0x00))
                }
            }
            assertTrue(ProxyListenerProbe.responds(server.localPort, timeoutMs = 2_000))
        }
    }

    @Test fun probeTreatsFullPoolCloseAsAlive() {
        ServerSocket(0).use { server ->
            thread { server.accept().close() }
            assertTrue(ProxyListenerProbe.responds(server.localPort, timeoutMs = 2_000))
        }
    }

    @Test fun probeDetectsStuckLoop() {
        ServerSocket(0).use { server ->
            // Bağlantı çekirdekte kabul edilir ama kimse yanıtlamaz: kilitli döngü.
            assertFalse(ProxyListenerProbe.responds(server.localPort, timeoutMs = 300))
        }
    }

    @Test fun probeDetectsMissingListener() {
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(ProxyListenerProbe.responds(port, timeoutMs = 300))
    }
}
