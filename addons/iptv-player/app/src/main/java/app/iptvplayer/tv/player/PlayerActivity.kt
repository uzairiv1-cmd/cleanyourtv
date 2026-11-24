package app.iptvplayer.tv.player

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.iptvplayer.tv.Item
import app.iptvplayer.tv.Kind
import app.iptvplayer.tv.R
import app.iptvplayer.tv.data.Store
import app.iptvplayer.tv.ui.ImageLoader
import app.iptvplayer.tv.ui.MonogramDrawable
import app.iptvplayer.tv.ui.SimpleAdapter
import app.iptvplayer.tv.ui.clipToBackground
import app.iptvplayer.tv.ui.focusPosition
import app.iptvplayer.tv.ui.formatClock
import app.iptvplayer.tv.ui.zoomOnFocus

/**
 * Full-screen player.
 * Live: Up/Down or Channel +/- change channel (with a banner), Right or Menu opens the channel list,
 * Left goes back to the previous channel, number keys jump to a channel, OK shows the controls.
 * Movies and episodes: Left/Right jump 10 seconds, OK shows the controls, and they resume where they stopped.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var controls: View
    private lateinit var banner: View
    private lateinit var drawer: View
    private lateinit var drawerList: RecyclerView
    private lateinit var error: View
    private lateinit var flash: TextView
    private lateinit var buffering: View
    private lateinit var playButton: ImageView
    private lateinit var seek: ProgressBar
    private val handler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { setControls(false) }
    private val hideBanner = Runnable { banner.visibility = View.GONE }
    private val hideFlash = Runnable { flash.visibility = View.GONE }
    private val tick = object : Runnable {
        override fun run() {
            updateTimes()
            handler.postDelayed(this, 500)
        }
    }
    private var retries = 0
    private var resumeUrl: String? = null
    private var lastChannel = -1
    private var typed = ""
    private val jumpTyped = Runnable {
        typed.toIntOrNull()?.let { n -> if (n in 1..PlayQueue.items.size) go(n - 1) }
        typed = ""
    }
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var speed = 1f

    private val live: Boolean get() = PlayQueue.current?.kind == Kind.LIVE

    private val drawerAdapter: SimpleAdapter<Item> = SimpleAdapter<Item>(R.layout.item_channel) { v, item, i ->
        v.findViewById<TextView>(R.id.number).text = (i + 1).toString()
        val img = v.findViewById<ImageView>(R.id.image)
        img.clipToBackground()
        ImageLoader.load(img, item.logo, MonogramDrawable(item.title), 120)
        v.findViewById<TextView>(R.id.title).apply {
            text = item.title
            setTextColor(if (i == PlayQueue.index) 0xFFFF7A2E.toInt() else 0xFFEEF0F4.toInt())
        }
        v.findViewById<View>(R.id.star).visibility = View.GONE
        v.setOnClickListener { setDrawer(false); go(i) }
        v.zoomOnFocus(1.03f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        view = findViewById(R.id.player_view)
        controls = findViewById(R.id.controls)
        banner = findViewById(R.id.banner)
        drawer = findViewById(R.id.drawer)
        drawerList = findViewById(R.id.drawer_list)
        error = findViewById(R.id.error)
        flash = findViewById(R.id.flash)
        buffering = findViewById(R.id.buffering)
        playButton = findViewById(R.id.btn_play)
        seek = findViewById(R.id.seek)
        view.keepScreenOn = true
        drawerList.layoutManager = LinearLayoutManager(this)
        drawerList.adapter = drawerAdapter

        playButton.setOnClickListener { togglePlay(); touch() }
        findViewById<View>(R.id.btn_prev).setOnClickListener { if (live) move(-1) else skip(-10_000); touch() }
        findViewById<View>(R.id.btn_next).setOnClickListener { if (live) move(1) else skip(10_000); touch() }
        findViewById<View>(R.id.btn_list).setOnClickListener { setControls(false); setDrawer(true) }
        findViewById<View>(R.id.btn_tracks).setOnClickListener { showOptions() }
        findViewById<View>(R.id.error_retry).setOnClickListener { play(PlayQueue.current ?: return@setOnClickListener) }
        findViewById<View>(R.id.error_next).setOnClickListener { move(1) }
        for (id in intArrayOf(R.id.btn_prev, R.id.btn_play, R.id.btn_next, R.id.btn_list, R.id.btn_tracks, R.id.error_retry, R.id.error_next)) {
            findViewById<View>(id).zoomOnFocus(1.12f)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!handleBack()) finish()
            }
        }
        if (PlayQueue.current == null) finish()
    }

    override fun onStart() {
        super.onStart()
        if (player == null) {
            val p = Players.create(this)
            p.addListener(listener)
            player = p
            view.player = p
        }
        play(PlayQueue.current ?: return)
        handler.post(tick)
    }

    override fun onStop() {
        savePosition()
        resumeUrl = null
        player?.let { it.removeListener(listener); it.release() }
        player = null
        view.player = null
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY) {
                retries = 0
                error.visibility = View.GONE
            }
            // Some live MPEG-TS streams "end" when the server reconnects: carry on instead of stopping.
            if (state == Player.STATE_ENDED && live) reconnect()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }

        override fun onPlayerError(e: PlaybackException) {
            val p = player ?: return
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition(); p.prepare(); return
            }
            if (live && retries < 3 && e.errorCode in RETRYABLE) { reconnect(); return }
            buffering.visibility = View.GONE
            findViewById<TextView>(R.id.error_text).text = getString(R.string.cannot_play, describe(e))
            findViewById<View>(R.id.error_next).visibility = if (live && PlayQueue.items.size > 1) View.VISIBLE else View.GONE
            setControls(false)
            error.visibility = View.VISIBLE
            findViewById<View>(R.id.error_retry).requestFocus()
        }
    }

    private fun reconnect() {
        retries++
        handler.postDelayed({ player?.let { it.seekToDefaultPosition(); it.prepare(); it.playWhenReady = true } }, 1500L * retries)
    }

    private fun play(item: Item) {
        val p = player ?: return
        val media = Players.mediaItem(item) ?: return
        val url = item.url ?: return
        savePosition()
        retries = 0
        error.visibility = View.GONE
        p.setMediaItem(media)
        if (item.kind == Kind.MOVIE || item.kind == Kind.EPISODE) {
            val pos = Store.position(this, url)
            if (pos > 0) p.seekTo(pos)
            resumeUrl = url
            p.playbackParameters = PlaybackParameters(speed)
        } else {
            resumeUrl = null
            p.playbackParameters = PlaybackParameters(1f)
        }
        p.prepare()
        p.playWhenReady = true
        if (PlayQueue.playlistId.isNotEmpty()) Store.addRecent(this, PlayQueue.playlistId, item)
        updateChrome(item)
        if (live) showBanner(item)
    }

    private fun updateChrome(item: Item) {
        val isLive = item.kind == Kind.LIVE
        val n = PlayQueue.items.size
        findViewById<View>(R.id.seek_row).visibility = if (isLive) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bottom_live).visibility = if (isLive) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.position).text = if (isLive && n > 1) "${PlayQueue.index + 1} / $n" else item.title
        findViewById<TextView>(R.id.hints).setText(if (isLive) R.string.hints_live else R.string.hints_vod)
        findViewById<View>(R.id.btn_list).visibility = if (isLive && n > 1) View.VISIBLE else View.GONE
        val showSteps = !isLive || n > 1
        findViewById<View>(R.id.btn_prev).visibility = if (showSteps) View.VISIBLE else View.INVISIBLE
        findViewById<View>(R.id.btn_next).visibility = if (showSteps) View.VISIBLE else View.INVISIBLE
        findViewById<ImageView>(R.id.btn_prev).setImageResource(if (isLive) R.drawable.ic_prev else R.drawable.ic_back10)
        findViewById<ImageView>(R.id.btn_next).setImageResource(if (isLive) R.drawable.ic_next else R.drawable.ic_fwd10)
        findViewById<TextView>(R.id.drawer_cap).text = getString(R.string.now_watching, PlayQueue.title.ifEmpty { item.title })
    }

    private fun showBanner(item: Item) {
        val logo = findViewById<ImageView>(R.id.banner_logo)
        logo.clipToBackground()
        ImageLoader.load(logo, item.logo, MonogramDrawable(item.title), 160)
        findViewById<TextView>(R.id.banner_cap).text = getString(R.string.channel_n, PlayQueue.index + 1)
        findViewById<TextView>(R.id.banner_title).text = item.title
        banner.visibility = View.VISIBLE
        banner.alpha = 0f
        banner.translationY = -20f
        banner.animate().alpha(1f).translationY(0f).setDuration(220).start()
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, 3500)
    }

    private fun updateTimes() {
        val p = player ?: return
        if (live || controls.visibility != View.VISIBLE) return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val pos = p.currentPosition
        findViewById<TextView>(R.id.time_now).text = formatClock(pos)
        findViewById<TextView>(R.id.time_left).text = "−" + formatClock((dur - pos).coerceAtLeast(0))
        seek.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
    }

    private fun savePosition() {
        val p = player ?: return
        val url = resumeUrl ?: return
        Store.savePosition(this, url, p.currentPosition, p.duration)
    }

    private fun go(i: Int) {
        if (i == PlayQueue.index) return
        lastChannel = PlayQueue.index
        PlayQueue.jump(i)?.let { play(it) }
    }

    private fun move(delta: Int) {
        lastChannel = PlayQueue.index
        PlayQueue.move(delta)?.let { play(it) }
    }

    private fun skip(ms: Long) {
        val p = player ?: return
        p.seekTo((p.currentPosition + ms).coerceAtLeast(0))
        flash.text = if (ms > 0) "+10 s" else "−10 s"
        flash.visibility = View.VISIBLE
        handler.removeCallbacks(hideFlash)
        handler.postDelayed(hideFlash, 700)
        updateTimes()
    }

    private fun togglePlay() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
    }

    private fun setControls(show: Boolean) {
        handler.removeCallbacks(hideControls)
        if (show) {
            controls.visibility = View.VISIBLE
            updateTimes()
            playButton.requestFocus()
            touch()
        } else {
            controls.visibility = View.GONE
        }
    }

    /** Keeps the controls up for a few more seconds after any press. */
    private fun touch() {
        handler.removeCallbacks(hideControls)
        if (controls.visibility == View.VISIBLE) handler.postDelayed(hideControls, 5000)
    }

    private fun setDrawer(show: Boolean) {
        if (show) {
            drawerAdapter.submit(PlayQueue.items)
            drawer.visibility = View.VISIBLE
            drawer.translationX = 120f
            drawer.animate().translationX(0f).setDuration(200).start()
            drawerList.focusPosition(PlayQueue.index)
        } else {
            drawer.visibility = View.GONE
        }
    }

    private fun handleBack(): Boolean = when {
        drawer.visibility == View.VISIBLE -> { setDrawer(false); true }
        controls.visibility == View.VISIBLE -> { setControls(false); true }
        else -> false
    }

    @Deprecated("Used only below Android 13")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        when {
            code == KeyEvent.KEYCODE_CHANNEL_UP -> { move(1); return true }
            code == KeyEvent.KEYCODE_CHANNEL_DOWN -> { move(-1); return true }
            code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                togglePlay(); return true
            }
            code == KeyEvent.KEYCODE_INFO -> { PlayQueue.current?.let { showBanner(it) }; return true }
            code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && live -> {
                typed += (code - KeyEvent.KEYCODE_0).toString()
                flash.text = typed
                flash.visibility = View.VISIBLE
                handler.removeCallbacks(hideFlash)
                handler.postDelayed(hideFlash, 1500)
                handler.removeCallbacks(jumpTyped)
                handler.postDelayed(jumpTyped, 1500)
                return true
            }
        }
        // The list, the controls or the error card have focus: let them handle the keys.
        if (drawer.visibility == View.VISIBLE || error.visibility == View.VISIBLE) {
            if (drawer.visibility == View.VISIBLE && code == KeyEvent.KEYCODE_DPAD_LEFT) { setDrawer(false); return true }
            return super.dispatchKeyEvent(event)
        }
        if (controls.visibility == View.VISIBLE) {
            touch()
            return super.dispatchKeyEvent(event)
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> if (live) { move(1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (live) { move(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU -> {
                if (live && PlayQueue.items.size > 1) { setDrawer(true); return true }
                if (!live && code == KeyEvent.KEYCODE_DPAD_RIGHT) { skip(10_000); return true }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (live && lastChannel >= 0) { go(lastChannel); return true }
                if (!live) { skip(-10_000); return true }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { setControls(true); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Audio, subtitles, picture fit and speed, in one list. */
    private fun showOptions() {
        val p = player ?: return
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        for (group in p.currentTracks.groups) {
            val type = group.type
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val name = listOfNotNull(f.label, f.language).firstOrNull() ?: "${i + 1}"
                val mark = if (group.isTrackSelected(i)) "✓  " else "     "
                labels.add(mark + getString(if (type == C.TRACK_TYPE_AUDIO) R.string.audio else R.string.subtitles) + ": " + name)
                actions.add {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .build()
                }
            }
        }
        if (p.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT }) {
            labels.add("     " + getString(R.string.subtitles_off))
            actions.add { p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
        }
        for ((mode, label) in listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to R.string.picture_fit,
            AspectRatioFrameLayout.RESIZE_MODE_FILL to R.string.picture_fill,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to R.string.picture_zoom,
        )) {
            labels.add((if (resizeMode == mode) "✓  " else "     ") + getString(label))
            actions.add { resizeMode = mode; view.resizeMode = mode }
        }
        if (!live) {
            for (s in floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)) {
                labels.add((if (speed == s) "✓  " else "     ") + getString(R.string.speed_n, if (s == 1f) "1×" else "${s}×"))
                actions.add { speed = s; p.playbackParameters = PlaybackParameters(s) }
            }
        }
        handler.removeCallbacks(hideControls)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.options)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setOnDismissListener { touch() }
            .show()
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
