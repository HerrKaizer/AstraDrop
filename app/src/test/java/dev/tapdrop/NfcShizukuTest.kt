package dev.tapdrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcShizukuTest {
    @Test
    fun parseNfcStateExtractsStates() {
        assertEquals("on", NfcShizuku.parseNfcState("mState=on"))
        assertEquals("off", NfcShizuku.parseNfcState("  mState=off  "))
        assertEquals("turning on", NfcShizuku.parseNfcState("  mState=turning on  "))
        assertEquals("turning off", NfcShizuku.parseNfcState("mState=turning off\n"))
        assertEquals(
            "on",
            NfcShizuku.parseNfcState("header line\n  mState=on\nother=value\n"),
        )
        assertEquals("", NfcShizuku.parseNfcState(""))
        assertEquals("", NfcShizuku.parseNfcState("NfcService dump\nno state here"))
    }

    @Test
    fun isAppFunctionSuccessMatchesCompactAndPrettyJson() {
        assertTrue(NfcShizuku.isAppFunctionSuccess("""{"response":{"isSuccessful":[true]}}"""))
        assertTrue(
            NfcShizuku.isAppFunctionSuccess(
                "{\n  \"response\": {\n    \"isSuccessful\": [\n      true\n    ]\n  }\n}",
            ),
        )
        assertFalse(NfcShizuku.isAppFunctionSuccess("""{"response":{"isSuccessful":[false]}}"""))
        assertFalse(NfcShizuku.isAppFunctionSuccess("Error: app function failed"))
        assertFalse(NfcShizuku.isAppFunctionSuccess(""))
        assertFalse(NfcShizuku.isAppFunctionSuccess(null))
    }

    @Test
    fun watchdogScriptsContainNoCarriageReturns() {
        assertFalse(NfcShizuku.WATCHDOG_LAUNCHER.contains('\r'))
        assertFalse(NfcShizuku.WATCHDOG_BODY.contains('\r'))
        assertTrue(NfcShizuku.WATCHDOG_LAUNCHER.contains("tapdrop_nfc_watch"))
        assertTrue(NfcShizuku.WATCHDOG_BODY.contains("tapdrop_nfc_watch"))
    }
}
