package app.iptvplayer.tv

import app.iptvplayer.tv.player.Backoff
import app.iptvplayer.tv.player.StallDetector
import app.iptvplayer.tv.player.StreamUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest {
    private val buffering = 2
    private val ready = 3

    @Test fun aMovingStreamIsFine() {
        val d = StallDetector()
        d.reset(0)
        for (t in 0..60_000L step 1000) assertFalse(d.check(t, true, ready, t))
    }

    @Test fun aFrozenPictureIsNoticedAfterTenSeconds() {
        val d = StallDetector()
        d.reset(0)
        for (t in 0..5_000L step 1000) assertFalse(d.check(t, true, ready, t))
        for (t in 6_000L..14_000L step 1000) assertFalse("at $t", d.check(t, true, ready, 5_000))
        assertTrue(d.check(15_000, true, ready, 5_000))
    }

    @Test fun longBufferingIsNoticedAfterFifteenSeconds() {
        val d = StallDetector()
        d.reset(0)
        for (t in 0..14_000L step 1000) assertFalse(d.check(t, true, buffering, 0))
        assertTrue(d.check(15_000, true, buffering, 0))
    }

    @Test fun shortBufferingThenPlayingIsFine() {
        val d = StallDetector()
        d.reset(0)
        for (t in 0..10_000L step 1000) assertFalse(d.check(t, true, buffering, 0))
        for (t in 11_000L..40_000L step 1000) assertFalse(d.check(t, true, ready, t))
    }

    @Test fun pausedIsNeverAStall() {
        val d = StallDetector()
        d.reset(0)
        for (t in 0..120_000L step 1000) assertFalse(d.check(t, false, ready, 7))
    }

    @Test fun backoffGrowsThenCaps() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), (1..7).map { Backoff.delayMs(it) })
        assertEquals(2_000L, Backoff.delayMs(0))
    }

    @Test fun alternateFormat() {
        assertEquals("http://h:80/live/u/p/42.ts", StreamUrls.alternate("http://h:80/live/u/p/42.m3u8"))
        assertEquals("http://h:80/live/u/p/42.m3u8?t=1", StreamUrls.alternate("http://h:80/live/u/p/42.ts?t=1"))
        assertNull(StreamUrls.alternate("http://h/movie/u/p/9.mp4"))
        assertNull(StreamUrls.alternate("http://cdn/stream/index.m3u8"))
    }
}
