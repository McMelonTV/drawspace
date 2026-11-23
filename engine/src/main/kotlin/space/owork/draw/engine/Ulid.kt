package space.owork.draw.engine

import java.security.SecureRandom

/**
 * Monotonic, lexicographically sortable 26-character Crockford Base32 ULID generator.
 * Used for document IDs, layer IDs, and stroke IDs so Git stroke chunks stay naturally
 * ordered and two-device merges never collide.
 */
object Ulid {
    private const val ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    private var lastTimestampMs = -1L
    private val lastRandom = ByteArray(10)

    @Synchronized
    fun next(timestampMs: Long = System.currentTimeMillis()): String {
        val ts = timestampMs.coerceAtLeast(0L)
        if (ts == lastTimestampMs) {
            incrementRandom(lastRandom)
        } else {
            lastTimestampMs = ts
            random.nextBytes(lastRandom)
        }
        return encode(ts, lastRandom)
    }

    private fun incrementRandom(bytes: ByteArray) {
        for (i in bytes.lastIndex downTo 0) {
            val next = (bytes[i].toInt() and 0xFF) + 1
            bytes[i] = (next and 0xFF).toByte()
            if (next <= 0xFF) return
        }
        // Overflow within the same millisecond is astronomically rare; re-seed.
        random.nextBytes(bytes)
    }

    private fun encode(timestampMs: Long, rand: ByteArray): String {
        val out = CharArray(26)
        // 48-bit timestamp -> 10 base32 chars
        var t = timestampMs
        for (i in 9 downTo 0) {
            out[i] = ENCODING[(t and 0x1FL).toInt()]
            t = t ushr 5
        }
        // 80-bit random -> 16 base32 chars
        var buffer = 0
        var bitsInBuffer = 0
        var idx = 10
        for (b in rand) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsInBuffer += 8
            while (bitsInBuffer >= 5) {
                bitsInBuffer -= 5
                out[idx++] = ENCODING[(buffer ushr bitsInBuffer) and 0x1F]
            }
        }
        return String(out)
    }
}
