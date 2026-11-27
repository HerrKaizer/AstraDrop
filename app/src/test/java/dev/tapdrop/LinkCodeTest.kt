package dev.tapdrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkCodeTest {
    @Test
    fun codesAreLongUrlSafeAndDifferent() {
        val seen = HashSet<String>()
        repeat(2000) {
            val c = LinkCode.new()
            assertEquals(LinkCode.LENGTH, c.length)
            assertTrue(c, c.matches(Regex("[A-Za-z0-9]+")))
            assertTrue("repeat: $c", seen.add(c))
        }
    }

    @Test
    fun matchesOnlyTheExactCode() {
        val c = LinkCode.new()
        assertTrue(LinkCode.matches(c, c))
        assertFalse(LinkCode.matches(c.dropLast(1), c))
        assertFalse(LinkCode.matches(null, c))
        assertFalse(LinkCode.matches(c.uppercase().takeIf { it != c } ?: "x", c))
    }
}
