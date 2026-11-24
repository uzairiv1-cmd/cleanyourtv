package app.iptvplayer.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import app.iptvplayer.tv.Item
import app.iptvplayer.tv.data.Http

/**
 * ExoPlayer set up for IPTV that runs for hours:
 * - a 20 to 50 second buffer (capped at 48 MB for 1 GB TVs), so a provider's short hiccup is never seen;
 * - each failed chunk or playlist reload retried 6 times before it counts as an error;
 * - another decoder tried if the TV's first one fails to start;
 * - the Wi-Fi and the CPU kept awake while playing, so the TV does not throttle Wi-Fi after a while.
 * The muted preview in Live TV gets a small buffer and no locks.
 */
object Players {
    @OptIn(UnstableApi::class)
    fun create(context: Context, preview: Boolean = false): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val sources = DefaultMediaSourceFactory(http)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val load = if (preview) {
            DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 10_000, 1_000, 2_000).build()
        } else {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(20_000, 50_000, 2_500, 5_000)
                .setTargetBufferBytes(48 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }
        val builder = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(sources)
            .setLoadControl(load)
        if (!preview) builder.setWakeMode(C.WAKE_MODE_NETWORK)
        return builder.build()
    }

    fun mediaItem(item: Item, url: String? = item.url): MediaItem? {
        val u = url ?: return null
        return MediaItem.Builder()
            .setUri(u)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
            .apply { if (u.substringBefore('?').endsWith(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
    }
}
