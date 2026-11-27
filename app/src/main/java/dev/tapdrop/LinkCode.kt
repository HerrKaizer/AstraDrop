package dev.tapdrop

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The secret part of the share link: 22 random letters and digits (about 131 bits), new for every
 * session and whenever the user taps New link. Nobody can guess it, so only phones that were
 * handed the link (tap or QR) can open the page.
 */
object LinkCode {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    const val LENGTH = 22
    private val rnd = SecureRandom()

    fun new(): String = buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[rnd.nextInt(ALPHABET.length)]) } }

    /** Constant-time comparison, so response timing says nothing about how much of a guess was right. */
    fun matches(given: String?, real: String): Boolean =
        given != null && MessageDigest.isEqual(given.toByteArray(), real.toByteArray())
}
