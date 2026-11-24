package dev.tapdrop

import org.junit.Assert.assertEquals
import org.junit.Test

class RuPluralTest {
    private fun f(n: Int) = HttpServer.ruPlural(n, "файл", "файла", "файлов")

    @Test
    fun forms() {
        mapOf(
            1 to "файл", 21 to "файл", 101 to "файл",
            2 to "файла", 3 to "файла", 4 to "файла", 22 to "файла", 34 to "файла",
            0 to "файлов", 5 to "файлов", 11 to "файлов", 12 to "файлов", 14 to "файлов", 111 to "файлов", 25 to "файлов",
        ).forEach { (n, want) -> assertEquals("n=$n", want, f(n)) }
    }
}
