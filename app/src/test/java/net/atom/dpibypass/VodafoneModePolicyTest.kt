package net.atom.dpibypass

import net.atom.dpibypass.data.AppFilterMode
import net.atom.dpibypass.vpn.VodafoneModePolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodafoneModePolicyTest {
    @Test fun enabledModeSetsGlobalTtlAfterCustomArguments() {
        val original = arrayOf("ciadpi", "-p", "1080", "--split", "2", "-g", "55")
        assertArrayEquals(
            arrayOf("ciadpi", "-p", "1080", "--split", "2", "-g", "55", "-g", "65", "-X"),
            VodafoneModePolicy.proxyArgs(original, true),
        )
    }

    @Test fun disabledModeLeavesArgumentsUntouched() {
        val original = arrayOf("ciadpi", "--split", "2")
        assertArrayEquals(original, VodafoneModePolicy.proxyArgs(original, false))
    }

    @Test fun enabledModeOverridesAppSelectionAndCapturesIpv6() {
        assertEquals(AppFilterMode.All, VodafoneModePolicy.filterMode(AppFilterMode.Include, true))
        assertEquals(AppFilterMode.All, VodafoneModePolicy.filterMode(AppFilterMode.Exclude, true))
        assertTrue(VodafoneModePolicy.captureIpv6(true))
    }

    @Test fun disabledModeRestoresTheSavedAppSelection() {
        assertEquals(AppFilterMode.Include, VodafoneModePolicy.filterMode(AppFilterMode.Include, false))
        assertFalse(VodafoneModePolicy.captureIpv6(false))
    }
}
