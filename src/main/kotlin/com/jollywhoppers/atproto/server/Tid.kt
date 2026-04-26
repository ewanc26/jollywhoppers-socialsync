package com.jollywhoppers.atproto.server

import java.security.SecureRandom
import java.time.Instant

/**
 * AT Protocol TID (Timestamp Identifier) generation.
 *
 * Zero-dependency, spec-compliant TID generation.
 * Spec: https://atproto.com/specs/tid
 *
 * A TID is a 13-character base32-sortable string encoding:
 * - 53-bit microsecond timestamp (11 characters)
 * - 5-bit clock identifier (2 characters)
 *
 * TIDs are lexicographically sortable by timestamp.
 */
object Tid {
    // AT Protocol uses a custom base-32 alphabet (not RFC 4648)
    // Characters are lexicographically ordered: 2 < 3 < ... < z
    private const val S32 = "234567abcdefghijklmnopqrstuvwxyz"

    // Reverse lookup
    private val S32_MAP: Map<Char, Int> = S32.mapIndexed { i, c -> c to i }.toMap()

    // Regex for valid TID: 13 chars, first char in 2-i, rest in 2-z
    private val TID_REGEX = Regex("^[234567abcdefghij][234567abcdefghijklmnopqrstuvwxyz]{12}$")

    // Module-level monotonic clock state
    @Volatile
    private var lastUs: Long = 0L

    private val clockId: Int = SecureRandom().nextInt(32)

    /**
     * Generate a TID for the current wall-clock time.
     * Monotonic — guaranteed to be strictly increasing within this JVM.
     */
    fun generate(): String {
        val nowUs = Instant.now().toEpochMilli() * 1000
        return makeTid(nextUs(nowUs))
    }

    /**
     * Generate a TID for a specific timestamp.
     * @param instant The timestamp to encode
     */
    fun generate(instant: Instant): String {
        val us = instant.toEpochMilli() * 1000
        return makeTid(nextUs(us))
    }

    /**
     * Generate a TID from milliseconds since Unix epoch.
     * @param epochMilli Milliseconds since 1970-01-01T00:00:00Z
     */
    fun generate(epochMilli: Long): String {
        val us = epochMilli * 1000
        return makeTid(nextUs(us))
    }

    /**
     * Validate a TID string.
     * @return true if the string is a well-formed AT Protocol TID
     */
    fun validate(tid: String): Boolean = TID_REGEX.matches(tid)

    /**
     * Decode a TID into its constituent parts.
     * @throws IllegalArgumentException if the TID is malformed
     */
    fun decode(tid: String): DecodedTid {
        require(validate(tid)) { "Invalid TID format: \"$tid\"" }

        val timestampUs = s32decode(tid.substring(0, 11))
        val clockId = s32decode(tid.substring(11))

        return DecodedTid(
            timestampUs = timestampUs,
            clockId = clockId,
            instant = Instant.ofEpochSecond(timestampUs / 1_000_000, (timestampUs % 1_000_000) * 1000)
        )
    }

    // ─── Internal helpers ─────────────────────────────────────

    private fun nextUs(targetUs: Long): Long {
        synchronized(this) {
            val us = if (targetUs <= lastUs) lastUs + 1 else targetUs
            lastUs = us
            return us
        }
    }

    private fun makeTid(us: Long): String {
        return s32encode(us).padStart(11, '2') + s32encode(clockId.toLong()).padStart(2, '2')
    }

    private fun s32encode(n: Long): String {
        if (n == 0L) return "2"
        val sb = StringBuilder()
        var v = n
        while (v > 0) {
            sb.insert(0, S32[(v % 32).toInt()])
            v /= 32
        }
        return sb.toString()
    }

    private fun s32decode(s: String): Long {
        var n = 0L
        for (c in s) {
            n = n * 32 + (S32_MAP[c] ?: 0)
        }
        return n
    }

    /**
     * Decoded TID components.
     */
    data class DecodedTid(
        /** Microseconds since the Unix epoch */
        val timestampUs: Long,
        /** Clock identifier (0-31) */
        val clockId: Int,
        /** The timestamp as an Instant (microsecond precision) */
        val instant: Instant
    )
}
