package app.iptvplayer.tv.player

import kotlin.math.min

/**
 * Notices a stream that is stuck: buffering for too long, or "playing" with a position that no longer
 * moves (a frozen picture). Fed once a second by the player. Pure, so it is tested on the JVM.
 * States are Media3's: 1 idle, 2 buffering, 3 ready, 4 ended.
 */
class StallDetector(private val frozenMs: Long = 10_000, private val bufferingMs: Long = 15_000) {
    private var lastPosition = Long.MIN_VALUE
    private var lastMoveAt = 0L
    private var bufferingSince = -1L

    fun reset(now: Long) {
        lastPosition = Long.MIN_VALUE
        lastMoveAt = now
        bufferingSince = -1L
    }

    /** True when the stream should be reopened. */
    fun check(now: Long, wantsToPlay: Boolean, state: Int, positionMs: Long): Boolean {
        if (!wantsToPlay) { reset(now); return false }
        if (state == STATE_BUFFERING) {
            if (bufferingSince < 0) bufferingSince = now
            if (now - bufferingSince >= bufferingMs) return true
        } else {
            bufferingSince = -1L
        }
        if (state == STATE_READY) {
            if (positionMs != lastPosition) {
                lastPosition = positionMs
                lastMoveAt = now
            } else if (now - lastMoveAt >= frozenMs) {
                return true
            }
        } else {
            lastMoveAt = now
        }
        return false
    }

    companion object {
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
    }
}

object Backoff {
    /** 2 s, 4 s, 8 s, 16 s, then every 30 s: patient with a provider that is down, quick after a blip. */
    fun delayMs(attempt: Int): Long = min(30_000L, 1_000L shl min(attempt.coerceAtLeast(1), 5))
}

object StreamUrls {
    /**
     * The same Xtream live channel in the other format: `/live/u/p/42.m3u8` <-> `/live/u/p/42.ts`.
     * Some panels serve one format better than the other, or stop serving one. Null for other addresses.
     */
    fun alternate(url: String): String? {
        val query = url.substringAfter('?', "")
        val path = url.substringBefore('?')
        if (!path.contains("/live/")) return null
        val swapped = when {
            path.endsWith(".m3u8", true) -> path.dropLast(5) + ".ts"
            path.endsWith(".ts", true) -> path.dropLast(3) + ".m3u8"
            else -> return null
        }
        return if (query.isEmpty()) swapped else "$swapped?$query"
    }
}
