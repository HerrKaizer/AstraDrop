package dev.tapdrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import dev.tapdrop.engine.AstraDaemon
import org.junit.Test

class EngineNfcTest {
    @Test
    fun parseNfcStateExtractsStates() {
        assertEquals("on", AstraDaemon.parseNfcState("mState=on"))
        assertEquals("off", AstraDaemon.parseNfcState("  mState=off  "))
        assertEquals("turning on", AstraDaemon.parseNfcState("  mState=turning on  "))
        assertEquals("turning off", AstraDaemon.parseNfcState("mState=turning off\n"))
        assertEquals(
            "on",
            AstraDaemon.parseNfcState("header line\n  mState=on\nother=value\n"),
        )
        assertEquals("", AstraDaemon.parseNfcState(""))
        assertEquals("", AstraDaemon.parseNfcState("NfcService dump\nno state here"))
    }

    @Test
    fun isAppFunctionSuccessMatchesCompactAndPrettyJson() {
        assertTrue(AstraDaemon.isAppFunctionSuccess("""{"response":{"isSuccessful":[true]}}"""))
        assertTrue(
            AstraDaemon.isAppFunctionSuccess(
                "{\n  \"response\": {\n    \"isSuccessful\": [\n      true\n    ]\n  }\n}",
            ),
        )
        assertFalse(AstraDaemon.isAppFunctionSuccess("""{"response":{"isSuccessful":[false]}}"""))
        assertFalse(AstraDaemon.isAppFunctionSuccess("Error: app function failed"))
        assertFalse(AstraDaemon.isAppFunctionSuccess(""))
        assertFalse(AstraDaemon.isAppFunctionSuccess(null))
    }

    @Test
    fun wifiNameFromWifiStatus() {
        val status = "Wifi is enabled\nWifi scanning is always available\n==== Primary ClientModeManager instance ====\n" +
            "Wifi is connected to \"Lomonosov_5G\"\nWifiInfo: SSID: \"Lomonosov_5G\", BSSID: 60:..."
        assertEquals("Lomonosov_5G", AstraDaemon.parseWifiName(status))
        assertEquals("", AstraDaemon.parseWifiName("Wifi is enabled\nWifi is not connected"))
        assertEquals("", AstraDaemon.parseWifiName(null))
    }
}
