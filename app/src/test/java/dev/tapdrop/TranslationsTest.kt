package dev.tapdrop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The translation files (assets/i18n): every key is text that really appears in the code (a typo
 * would silently show English), every language has the same keys, and placeholders (%s, %d, %1...)
 * and **bold** / <b> markers survive translation.
 */
class TranslationsTest {
    private val dir = File("src/main/assets/i18n")
    private val source by lazy {
        File("src/main/java").walkTopDown().filter { it.extension == "kt" || it.extension == "java" }
            .joinToString("\n") { it.readText() }
    }

    /** Flat {"key": "value"} JSON, enough for these files (org.json isn't available in unit tests). */
    private fun parse(f: File): LinkedHashMap<String, String> {
        val s = f.readText()
        val out = LinkedHashMap<String, String>()
        var i = 0
        fun str(): String {
            while (s[i] != '"') i++
            i++
            val b = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    b.append(when (s[i]) { 'n' -> '\n'; 't' -> '\t'; 'u' -> s.substring(i + 1, i + 5).toInt(16).toChar().also { i += 4 }; else -> s[i] })
                } else b.append(s[i])
                i++
            }
            i++
            return b.toString()
        }
        while (true) {
            val q = s.indexOf('"', i)
            if (q < 0) break
            i = q
            val k = str()
            while (s[i] != ':') i++
            val v = str()
            assertTrue("${f.name}: \"$k\" twice", out.put(k, v) == null)
        }
        return out
    }

    private fun langs() = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }

    @Test
    fun keysAreRealTexts() {
        for (f in langs()) for (k in parse(f).keys) {
            val parts = if (k.contains('|')) k.split('|') else listOf(k)
            // in the code a newline is written \n and an apostrophe in a JS string \'
            val missing = parts.filter { p ->
                val code = p.replace("\n", "\\n")
                !source.contains(code) && !source.contains(code.replace("'", "\\'"))
            }
            assertTrue("${f.name}: not in the code: $missing", missing.isEmpty())
        }
    }

    @Test
    fun everyLanguageHasTheSameKeys() {
        val all = langs()
        assertTrue("expected 8 translations, found ${all.map { it.name }}", all.size == 8)
        val ref = parse(all.first()).keys
        for (f in all.drop(1)) {
            val k = parse(f).keys
            assertEquals("${f.name} vs ${all.first().name}: missing ${ref - k}, extra ${k - ref}", ref, k)
        }
    }

    private fun marks(s: String) = Regex("%[sd]|%[0-9]|\\*\\*|</?[bi]>").findAll(s).map { it.value }.sorted().toList()

    @Test
    fun placeholdersSurvive() {
        for (f in langs()) for ((k, v) in parse(f)) {
            if (k.contains('|')) {
                val want = marks(k.split('|')[0])
                v.split('|').forEach { form -> assertEquals("${f.name}: \"$k\" → \"$form\"", want, marks(form)) }
            } else {
                assertEquals("${f.name}: \"$k\" → \"$v\"", marks(k), marks(v))
            }
        }
    }
}
