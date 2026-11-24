package dev.tapdrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkWordsTest {
    @Test
    fun wordsAreUniqueAndUrlSafe() {
        val w = LinkWords.words
        assertEquals("duplicate words", w.size, w.toSet().size)
        w.forEach { assertTrue("bad word: $it", it.matches(Regex("[a-z]+(-[a-z]+)?"))) }
        assertTrue(w.size >= 100)
    }

    @Test
    fun pairUsesTwoDifferentListWords() {
        repeat(500) {
            val p = LinkWords.pair()
            val split = LinkWords.words.flatMap { a ->
                LinkWords.words.filter { b -> "$a-$b" == p }.map { b -> a to b }
            }
            assertTrue("not two list words: $p", split.isNotEmpty())
            split.forEach { (a, b) -> assertNotEquals(a, b) }
        }
    }
}
