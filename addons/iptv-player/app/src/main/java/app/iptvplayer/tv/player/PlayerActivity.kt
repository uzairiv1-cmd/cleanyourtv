package app.iptvplayer.tv.player

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.iptvplayer.tv.Item
import app.iptvplayer.tv.Kind
import app.iptvplayer.tv.R
import app.iptvplayer.tv.data.Http
import app.iptvplayer.tv.data.Store

/**
 * Media3 player. Live: Up/Down and Channel +/- change channel while the controls are hidden, and a
 * stream that drops is reconnected. Movies and episodes start where they were left.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var osd: TextView
    private lateinit var error: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideOsd = Runnable { osd.visibility = View.GONE }
    private var retries = 0
    private var resumeUrl: String? = null

    private val live: Boolean get() = PlayQueue.current?.kind == Kind.LIVE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        view = findViewById(R.id.player_view)
        osd = findViewById(R.id.osd)
        error = findViewById(R.id.error)
        view.keepScreenOn = true
        if (PlayQueue.current == null) finish()
    }

    override fun onStart() {
        super.onStart()
        if (player == null) {
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(Http.USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
            val p = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(http))
                .build()
            p.addListener(listener)
            player = p
            view.setPlayer(p)
        }
        view.requestFocus()
        play(PlayQueue.current ?: return)
    }

    override fun onStop() {
        savePosition()
        resumeUrl = null // saved; the next start reads it back
        player?.let { it.removeListener(listener); it.release() }
        player = null
        view.setPlayer(null)
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                retries = 0
                error.visibility = View.GONE
            }
            // Some live MPEG-TS streams "end" when the server reconnects: carry on instead of stopping.
            if (state == Player.STATE_ENDED && live) reconnect()
        }

        override fun onPlayerError(e: PlaybackException) {
            val p = player ?: return
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition(); p.prepare(); return
            }
            if (live && retries < 3 && e.errorCode in RETRYABLE) { reconnect(); return }
            error.text = getString(R.string.cannot_play, describe(e))
            error.visibility = View.VISIBLE
        }
    }

    private fun reconnect() {
        retries++
        handler.postDelayed({ player?.let { it.seekToDefaultPosition(); it.prepare(); it.playWhenReady = true } }, 1500L * retries)
    }

    private fun play(item: Item) {
        val p = player ?: return
        val url = item.url ?: return
        savePosition()
        retries = 0
        error.visibility = View.GONE
        val media = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).build())
            .apply { if (url.substringBefore('?').endsWith(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        p.setMediaItem(media)
        if (item.kind == Kind.MOVIE || item.kind == Kind.EPISODE) {
            val pos = Store.position(this, url)
            if (pos > 0) p.seekTo(pos)
            resumeUrl = url
        } else {
            resumeUrl = null
        }
        p.prepare()
        p.playWhenReady = true
        view.setControllerAutoShow(item.kind != Kind.LIVE)
        showOsd(item)
    }

    private fun savePosition() {
        val p = player ?: return
        val url = resumeUrl ?: return
        Store.savePosition(this, url, p.currentPosition, p.duration)
    }

    private fun showOsd(item: Item) {
        val n = PlayQueue.items.size
        osd.text = if (n > 1) "${PlayQueue.index + 1}/$n   ${item.title}" else item.title
        osd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, 4000)
    }

    private fun zap(delta: Int) {
        PlayQueue.move(delta)?.let { play(it) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val controlsHidden = !view.isControllerFullyVisible
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (live && controlsHidden) { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (live && controlsHidden) { zap(1); return true }
                KeyEvent.KEYCODE_INFO -> { PlayQueue.current?.let(::showOsd); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> getString(R.string.err_http)
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> getString(R.string.err_network)
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> getString(R.string.err_format)
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> getString(R.string.err_decoder)
        else -> e.errorCodeName
    }

    private companion object {
        val RETRYABLE = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        )
    }
}
