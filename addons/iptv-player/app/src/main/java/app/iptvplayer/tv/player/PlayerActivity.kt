package app.iptvplayer.tv.player

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
import app.iptvplayer.tv.Category
import app.iptvplayer.tv.Item
import app.iptvplayer.tv.Kind
import app.iptvplayer.tv.R
import app.iptvplayer.tv.Section
import app.iptvplayer.tv.data.Session
import app.iptvplayer.tv.data.Store
import app.iptvplayer.tv.source.Epg
import app.iptvplayer.tv.source.Programme
import app.iptvplayer.tv.source.Source
import app.iptvplayer.tv.ui.ImageLoader
import app.iptvplayer.tv.ui.MonogramDrawable
import app.iptvplayer.tv.ui.SimpleAdapter
import app.iptvplayer.tv.ui.clipToBackground
import app.iptvplayer.tv.ui.dp
import app.iptvplayer.tv.ui.focusPosition
import app.iptvplayer.tv.ui.formatClock
import app.iptvplayer.tv.ui.zoomOnFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.abs

/**
 * Full-screen player.
 *
 * Live TV, all panels over the picture: OK opens the channel menu (◀ ▶ switch groups), Up/Down or
 * Channel +/- change channel with the info bar, Info shows the programme, Left returns to the previous
 * channel, number keys jump. Colour keys: red favourite, green TV guide, yellow all channels, blue groups.
 * Menu opens audio, subtitles and picture. On touch screens: tap for the info bar and a toolbar,
 * swipe up or down to change channel.
 *
 * Movies and episodes: OK shows the controls, Left/Right jump 10 s, they resume where they stopped.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private enum class Overlay { NONE, MENU, GUIDE, GROUPS }

    private class Panel(val root: View) {
        val tab: TextView = root.findViewById(R.id.panel_tab)
        val list: RecyclerView = root.findViewById(R.id.panel_list)
        val message: TextView = root.findViewById(R.id.panel_message)
        val legend: LinearLayout = root.findViewById(R.id.panel_legend)
    }

    private var player: ExoPlayer? = null
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var view: PlayerView
    private lateinit var controls: View
    private lateinit var error: View
    private lateinit var flash: TextView
    private lateinit var buffering: View
    private lateinit var playButton: ImageView
    private lateinit var seek: ProgressBar
    private lateinit var reconnectPill: TextView
    private lateinit var infoBar: View
    private lateinit var descPanel: View
    private lateinit var touchBar: View
    private lateinit var scrim: View
    private lateinit var cornerLogo: View
    private lateinit var clockBadge: TextView
    private lateinit var menu: Panel
    private lateinit var guide: Panel
    private lateinit var groups: Panel

    private var source: Source? = null
    private var isTouch = false
    private var overlay = Overlay.NONE
    private var guideReturnsTo = Overlay.NONE
    private var categories: List<Category> = emptyList()
    private var menuCategory: Category? = null
    private var menuItems: List<Item> = emptyList()
    private var menuSelected = 0
    private var guideList: List<Programme> = emptyList()
    private val epg = HashMap<String, List<Programme>>()
    private val epgLoading = HashSet<String>()
    private val timeFormat by lazy { android.text.format.DateFormat.getTimeFormat(this) }

    private val hideControls = Runnable { setControls(false) }
    private val hideInfo = Runnable { setInfo(false) }
    private val hideFlash = Runnable { flash.visibility = View.GONE }
    private val tick = object : Runnable {
        override fun run() {
            updateTimes()
            if (infoBar.visibility == View.VISIBLE) PlayQueue.current?.let { bindInfoEpg(it) }
            handler.postDelayed(this, 1000)
        }
    }

    // Recovery (see Recovery.kt).
    private val detector = StallDetector()
    private var attempts = 0
    private var reconnectPending = false
    private var playingSince = 0L
    private var currentUrl: String? = null
    private val reconnectNow = Runnable { reopen() }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val watch = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && !reconnectPending && error.visibility != View.VISIBLE) {
                val now = SystemClock.elapsedRealtime()
                if (attempts > 0 && p.isPlaying && now - playingSince > 30_000) attempts = 0
                if (detector.check(now, p.playWhenReady, p.playbackState, p.currentPosition)) scheduleReconnect(immediate = true)
            }
            handler.postDelayed(this, 1000)
        }
    }

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
    private fun key(i: Item) = i.kind.name + ":" + i.id
    private fun pid() = PlayQueue.playlistId.ifEmpty { Store.active(this)?.id.orEmpty() }
    private fun isFav(i: Item) = pid().isNotEmpty() && Store.isFavorite(this, pid(), i)

    // Channel menu rows: number, logo, name, favourite, what's on, minutes left, progress.
    private val menuAdapter: SimpleAdapter<Item> = SimpleAdapter(R.layout.item_menu_row) { v, item, i ->
        v.findViewById<TextView>(R.id.number).text = (i + 1).toString()
        val img = v.findViewById<ImageView>(R.id.image)
        img.clipToBackground()
        ImageLoader.load(img, item.logo, MonogramDrawable(item.title), 80)
        v.findViewById<TextView>(R.id.title).text = item.title
        v.findViewById<View>(R.id.star).visibility = if (isFav(item)) View.VISIBLE else View.GONE
        v.isActivated = PlayQueue.current?.let { key(it) == key(item) } == true
        bindRowEpg(v, item)
        loadEpg(item) { if (menuItems.getOrNull(i)?.let(::key) == key(item)) menuAdapter.notifyItemChanged(i) }
        v.setOnClickListener { playFromMenu(i) }
        v.setOnLongClickListener { toggleFavorite(item); menuAdapter.notifyItemChanged(i); true }
        v.setOnFocusChangeListener { _, has -> if (has) menuSelected = i }
    }

    private val guideAdapter: SimpleAdapter<Programme> = SimpleAdapter(R.layout.item_simple_row) { v, p, _ ->
        val on = p.isOn(System.currentTimeMillis())
        v.findViewById<TextView>(R.id.a).apply {
            text = "${timeFormat.format(Date(p.start))} – ${timeFormat.format(Date(p.end))}"
            minWidth = dp(92)
            setTextColor(if (on) 0xFFFF7A2E.toInt() else 0xFF9AA0AA.toInt())
        }
        v.findViewById<TextView>(R.id.b).apply { text = p.title; paint.isFakeBoldText = on }
        v.findViewById<TextView>(R.id.c).apply {
            text = if (on) getString(R.string.now_badge) else ""
            setTextColor(0xFFFF4D4D.toInt())
        }
        v.isActivated = on
        v.setOnClickListener { showDescription(p, PlayQueue.current) }
    }

    private val groupsAdapter: SimpleAdapter<Category> = SimpleAdapter(R.layout.item_simple_row) { v, c, i ->
        v.findViewById<TextView>(R.id.a).text = (i + 1).toString()
        v.findViewById<TextView>(R.id.b).text = c.name
        v.findViewById<TextView>(R.id.c).text = ""
        v.isActivated = c.id == (PlayQueue.category?.id ?: "")
        v.setOnClickListener { openMenu(c) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        view = findViewById(R.id.player_view)
        controls = findViewById(R.id.controls)
        error = findViewById(R.id.error)
        flash = findViewById(R.id.flash)
        buffering = findViewById(R.id.buffering)
        playButton = findViewById(R.id.btn_play)
        seek = findViewById(R.id.seek)
        reconnectPill = findViewById(R.id.reconnect)
        infoBar = findViewById(R.id.info_bar)
        descPanel = findViewById(R.id.desc_panel)
        touchBar = findViewById(R.id.touch_bar)
        scrim = findViewById(R.id.scrim)
        cornerLogo = findViewById(R.id.corner_logo)
        clockBadge = findViewById(R.id.clock_badge)
        menu = Panel(findViewById(R.id.menu_panel))
        guide = Panel(findViewById(R.id.guide_panel))
        groups = Panel(findViewById(R.id.groups_panel))
        view.keepScreenOn = true
        source = Session.source(this)
        isTouch = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        for ((panel, adapter) in listOf(menu to menuAdapter, guide to guideAdapter, groups to groupsAdapter)) {
            panel.list.layoutManager = LinearLayoutManager(this)
            panel.list.adapter = adapter
            panel.list.itemAnimator = null
        }
        legend(menu, listOf(
            Triple(0xFFDDDDDD.toInt(), R.string.key_info) { showDescription(null, menuItems.getOrNull(menuSelected)) },
            Triple(0xFFE53935.toInt(), R.string.key_fav) { menuItems.getOrNull(menuSelected)?.let { toggleFavorite(it); menuAdapter.notifyItemChanged(menuSelected) } },
            Triple(0xFF43A047.toInt(), R.string.key_guide) { menuItems.getOrNull(menuSelected)?.let { openGuide(it) } },
            Triple(0xFFFDD835.toInt(), R.string.key_all) { openMenu(allChannels()) },
            Triple(0xFF1E88E5.toInt(), R.string.key_groups) { openGroups() },
        ))
        legend(guide, listOf(Triple(0xFFDDDDDD.toInt(), R.string.key_back) { closeOverlay() }))
        legend(groups, listOf(
            Triple(0xFFDDDDDD.toInt(), R.string.key_open) { },
            Triple(0xFFFDD835.toInt(), R.string.key_all) { openMenu(allChannels()) },
        ))

        playButton.setOnClickListener { togglePlay(); touch() }
        findViewById<View>(R.id.btn_prev).setOnClickListener { skip(-10_000); touch() }
        findViewById<View>(R.id.btn_next).setOnClickListener { skip(10_000); touch() }
        findViewById<View>(R.id.btn_tracks).setOnClickListener { showOptions() }
        findViewById<View>(R.id.error_retry).setOnClickListener { play(PlayQueue.current ?: return@setOnClickListener) }
        findViewById<View>(R.id.error_next).setOnClickListener { move(1) }
        for (id in intArrayOf(R.id.btn_prev, R.id.btn_play, R.id.btn_next, R.id.btn_tracks, R.id.error_retry, R.id.error_next)) {
            findViewById<View>(id).zoomOnFocus(1.12f)
        }
        scrim.setOnClickListener { closeOverlay() }
        findViewById<View>(R.id.t_channels).setOnClickListener { openMenu() }
        findViewById<View>(R.id.t_groups).setOnClickListener { openGroups() }
        findViewById<View>(R.id.t_guide).setOnClickListener { PlayQueue.current?.let { openGuide(it) } }
        findViewById<View>(R.id.t_info).setOnClickListener { toggleDescription() }
        findViewById<View>(R.id.t_options).setOnClickListener { showOptions() }
        findViewById<View>(R.id.t_close).setOnClickListener { finish() }

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                when {
                    overlay != Overlay.NONE -> closeOverlay()
                    live -> setInfo(infoBar.visibility != View.VISIBLE)
                    else -> setControls(controls.visibility != View.VISIBLE)
                }
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dy = e2.y - (e1?.y ?: e2.y)
                val dx = e2.x - (e1?.x ?: e2.x)
                if (!live || overlay != Overlay.NONE || abs(dy) < dp(60) || abs(dy) < abs(dx)) return false
                move(if (dy < 0) 1 else -1)
                return true
            }
        })
        view.setOnTouchListener { v, ev -> if (ev.action == MotionEvent.ACTION_UP) v.performClick(); gestures.onTouchEvent(ev) }

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!handleBack()) finish()
            }
        }
        if (PlayQueue.current == null) finish()
    }

    private fun legend(panel: Panel, keys: List<Triple<Int, Int, () -> Unit>>) {
        panel.legend.removeAllViews()
        for ((color, label, action) in keys) {
            val t = TextView(this)
            t.text = getString(label)
            t.textSize = 9f
            t.setTextColor(0xFFDDDDDD.toInt())
            t.gravity = Gravity.CENTER_VERTICAL
            t.setPadding(dp(6), dp(5), dp(6), dp(5))
            val dot: Drawable? = getDrawable(R.drawable.key_dot)?.mutate()
            dot?.setTintList(ColorStateList.valueOf(color))
            t.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
            t.compoundDrawablePadding = dp(4)
            t.setBackgroundResource(R.drawable.bg_menu_row)
            t.isFocusable = false
            t.setOnClickListener { action() }
            panel.legend.addView(t)
        }
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
        handler.post(watch)
        watchNetwork()
    }

    override fun onStop() {
        savePosition()
        resumeUrl = null
        player?.let { it.removeListener(listener); it.release() }
        player = null
        view.player = null
        handler.removeCallbacksAndMessages(null)
        reconnectPending = false
        networkCallback?.let { cb ->
            try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (e: RuntimeException) { /* already gone */ }
        }
        networkCallback = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** When the network comes back after a drop, reconnect at once instead of waiting for the next try. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post {
                    if (reconnectPending || error.visibility == View.VISIBLE) {
                        handler.removeCallbacks(reconnectNow)
                        error.visibility = View.GONE
                        reopen()
                    }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: RuntimeException) {
            // Some TVs limit network callbacks; the timed retries still run.
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY) error.visibility = View.GONE
            // Some live MPEG-TS streams "end" when the server reconnects: carry on instead of stopping.
            if (state == Player.STATE_ENDED && live) scheduleReconnect(immediate = true)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            if (isPlaying) {
                playingSince = SystemClock.elapsedRealtime()
                reconnectPill.visibility = View.GONE
            }
        }

        override fun onPlayerError(e: PlaybackException) {
            val p = player ?: return
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition(); p.prepare(); return
            }
            // Live TV keeps trying for as long as it is on screen; a movie gives up after 6 tries.
            // What can never work on this TV (an unsupported codec, DRM) says so at once.
            val fatal = e.errorCode in FATAL || e.errorCode in 6000..6999
            if (!fatal && (live || attempts < 6)) { scheduleReconnect(); return }
            showError(e)
        }
    }

    private fun showError(e: PlaybackException) {
        handler.removeCallbacks(reconnectNow)
        reconnectPending = false
        buffering.visibility = View.GONE
        reconnectPill.visibility = View.GONE
        findViewById<TextView>(R.id.error_text).text = getString(R.string.cannot_play, describe(e))
        findViewById<View>(R.id.error_next).visibility = if (live && PlayQueue.items.size > 1) View.VISIBLE else View.GONE
        setControls(false)
        error.visibility = View.VISIBLE
        findViewById<View>(R.id.error_retry).requestFocus()
    }

    /** Reopens the stream after a pause that grows with each failure (2, 4, 8, 16, then 30 s). */
    private fun scheduleReconnect(immediate: Boolean = false) {
        if (reconnectPending) return
        attempts++
        reconnectPending = true
        reconnectPill.text = getString(R.string.reconnecting, attempts)
        reconnectPill.visibility = View.VISIBLE
        handler.postDelayed(reconnectNow, if (immediate && attempts == 1) 0L else Backoff.delayMs(attempts))
    }

    private fun reopen() {
        reconnectPending = false
        val p = player ?: return
        val item = PlayQueue.current ?: return
        detector.reset(SystemClock.elapsedRealtime())
        if (live) {
            // On every second failure of an Xtream channel, try its other format (.m3u8 <-> .ts).
            if (attempts >= 2 && attempts % 2 == 0) StreamUrls.alternate(currentUrl ?: item.url.orEmpty())?.let { currentUrl = it }
            p.setMediaItem(Players.mediaItem(item, currentUrl) ?: return)
        } else {
            p.setMediaItem(Players.mediaItem(item, currentUrl) ?: return, p.currentPosition)
        }
        p.prepare()
        p.playWhenReady = true
    }

    private fun play(item: Item) {
        val p = player ?: return
        val media = Players.mediaItem(item) ?: return
        val url = item.url ?: return
        savePosition()
        handler.removeCallbacks(reconnectNow)
        reconnectPending = false
        attempts = 0
        currentUrl = url
        detector.reset(SystemClock.elapsedRealtime())
        reconnectPill.visibility = View.GONE
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
        val pid = pid()
        if (pid.isNotEmpty()) {
            Store.addRecent(this, pid, item)
            PlayQueue.category?.let { c -> if (item.kind == Kind.LIVE) Store.setLastLive(this, pid, c.id, c.name, item) }
        }
        updateChrome(item)
        if (item.kind == Kind.LIVE) setInfo(true)
    }

    private fun updateChrome(item: Item) {
        findViewById<TextView>(R.id.position).text = item.title
        findViewById<TextView>(R.id.hints).setText(R.string.hints_vod)
    }

    // Info bar and description.

    private fun setInfo(show: Boolean) {
        handler.removeCallbacks(hideInfo)
        if (!show) {
            infoBar.visibility = View.GONE
            descPanel.visibility = View.GONE
            touchBar.visibility = View.GONE
            clockBadge.visibility = View.GONE
            if (overlay == Overlay.NONE) cornerLogo.visibility = View.GONE
            return
        }
        val item = PlayQueue.current ?: return
        findViewById<TextView>(R.id.info_number).text = (PlayQueue.index + 1).toString()
        val logo = findViewById<ImageView>(R.id.info_logo)
        logo.clipToBackground()
        ImageLoader.load(logo, item.logo, MonogramDrawable(item.title), 120)
        findViewById<TextView>(R.id.info_name).text = item.title
        findViewById<View>(R.id.info_star).visibility = if (isFav(item)) View.VISIBLE else View.INVISIBLE
        bindInfoEpg(item)
        loadEpg(item) { if (PlayQueue.current?.let(::key) == key(item)) bindInfoEpg(item) }
        infoBar.visibility = View.VISIBLE
        clockBadge.visibility = View.VISIBLE
        cornerLogo.visibility = View.VISIBLE
        if (isTouch) touchBar.visibility = View.VISIBLE
        handler.postDelayed(hideInfo, if (isTouch) 7000 else 6000)
    }

    private fun bindInfoEpg(item: Item) {
        val now = System.currentTimeMillis()
        val clock = timeFormat.format(Date(now))
        findViewById<TextView>(R.id.info_clock).text = clock
        clockBadge.text = clock
        val (n, x) = Epg.nowNext(epg[key(item)].orEmpty(), now)
        findViewById<TextView>(R.id.now_time).text = n?.let { timeFormat.format(Date(it.start)) } ?: ""
        findViewById<TextView>(R.id.now_title).text = n?.title ?: getString(R.string.live_channel)
        findViewById<TextView>(R.id.now_left).text = n?.let { getString(R.string.minutes_plus, it.minutesLeft(now)) } ?: ""
        findViewById<TextView>(R.id.next_time).text = x?.let { timeFormat.format(Date(it.start)) } ?: ""
        findViewById<TextView>(R.id.next_title).text = x?.title ?: ""
        findViewById<TextView>(R.id.next_left).text = x?.let { getString(R.string.minutes_plus, it.minutes) } ?: ""
        findViewById<ProgressBar>(R.id.info_progress).progress = ((n?.progress(now) ?: 0f) * 1000).toInt()
    }

    private fun toggleDescription() {
        if (descPanel.visibility == View.VISIBLE) { descPanel.visibility = View.GONE; return }
        val item = PlayQueue.current ?: return
        setInfo(true)
        showDescription(null, item)
    }

    /** The programme's title, times and description; [p] null means "what is on now" on [item]. */
    private fun showDescription(p: Programme?, item: Item?) {
        item ?: return
        val now = System.currentTimeMillis()
        val prog = p ?: Epg.nowNext(epg[key(item)].orEmpty(), now).first
        findViewById<TextView>(R.id.desc_title).text = prog?.title ?: item.title
        findViewById<TextView>(R.id.desc_meta).text = prog?.let {
            "${item.title}  ·  ${timeFormat.format(Date(it.start))} – ${timeFormat.format(Date(it.end))}"
        } ?: getString(R.string.no_guide)
        findViewById<TextView>(R.id.desc_text).text = prog?.description.orEmpty()
        descPanel.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 12_000)
    }

    // TV guide data, cached per channel for this screen.

    private fun loadEpg(item: Item, then: () -> Unit) {
        val k = key(item)
        if (item.kind != Kind.LIVE || epg.containsKey(k) || !epgLoading.add(k)) return
        val src = source ?: return
        scope.launch {
            val list = try { src.epg(item) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            epg[k] = list
            epgLoading.remove(k)
            then()
        }
    }

    private fun bindRowEpg(v: View, item: Item) {
        val now = System.currentTimeMillis()
        val n = Epg.nowNext(epg[key(item)].orEmpty(), now).first
        v.findViewById<TextView>(R.id.prog).text = n?.title ?: ""
        v.findViewById<TextView>(R.id.left).text = n?.let { getString(R.string.minutes_plus, it.minutesLeft(now)) } ?: ""
        v.findViewById<ProgressBar>(R.id.mini).apply {
            visibility = if (n != null) View.VISIBLE else View.INVISIBLE
            progress = ((n?.progress(now) ?: 0f) * 1000).toInt()
        }
    }

    // Panels: channel menu, TV guide, groups.

    private fun showOverlay(o: Overlay) {
        overlay = o
        menu.root.visibility = if (o == Overlay.MENU) View.VISIBLE else View.GONE
        guide.root.visibility = if (o == Overlay.GUIDE) View.VISIBLE else View.GONE
        groups.root.visibility = if (o == Overlay.GROUPS) View.VISIBLE else View.GONE
        scrim.visibility = if (o == Overlay.NONE) View.GONE else View.VISIBLE
        cornerLogo.visibility = if (o == Overlay.NONE && infoBar.visibility != View.VISIBLE) View.GONE else View.VISIBLE
        if (o != Overlay.NONE) {
            handler.removeCallbacks(hideInfo)
            infoBar.visibility = View.GONE
            descPanel.visibility = View.GONE
            touchBar.visibility = View.GONE
            setControls(false)
        }
    }

    private fun closeOverlay() {
        if (overlay == Overlay.GUIDE && guideReturnsTo == Overlay.MENU) {
            showOverlay(Overlay.MENU)
            menu.list.focusPosition(menuSelected)
            return
        }
        showOverlay(Overlay.NONE)
    }

    private fun favoritesCategory() = Category(Category.FAVORITES, getString(R.string.favorites))
    private fun allChannels() = Category(Category.ALL, getString(R.string.key_all))

    private fun ensureCategories(then: () -> Unit) {
        if (categories.isNotEmpty()) { then(); return }
        val src = source ?: return
        scope.launch {
            categories = listOf(favoritesCategory()) + try {
                src.categories(Section.LIVE)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            then()
        }
    }

    /** Opens the channel menu on [c], or on the list being watched. */
    private fun openMenu(c: Category? = null) {
        showOverlay(Overlay.MENU)
        val current = PlayQueue.category
        if (c == null || c.id == current?.id) {
            setMenu(current, PlayQueue.items, PlayQueue.index)
        } else {
            loadGroup(c)
        }
        ensureCategories { }
    }

    private fun setMenu(c: Category?, items: List<Item>, focus: Int) {
        menuCategory = c
        menuItems = items
        menu.tab.text = getString(R.string.group_tab, c?.name ?: PlayQueue.title.ifEmpty { getString(R.string.channels) }, items.size)
        menu.message.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        menu.message.text = getString(R.string.nothing_here)
        menuAdapter.submit(items)
        menuSelected = focus.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (items.isNotEmpty()) menu.list.focusPosition(menuSelected)
    }

    private fun loadGroup(c: Category) {
        val src = source ?: return
        menu.tab.text = c.name
        menu.message.text = getString(R.string.loading)
        menu.message.visibility = View.VISIBLE
        menuAdapter.submit(emptyList())
        scope.launch {
            val items = try {
                if (c.id == Category.FAVORITES) Store.favorites(this@PlayerActivity, pid()).filter { it.kind == Kind.LIVE }
                else src.items(Section.LIVE, c)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            if (overlay != Overlay.MENU) return@launch
            val playing = PlayQueue.current?.let(::key)
            setMenu(c, items, items.indexOfFirst { key(it) == playing }.coerceAtLeast(0))
        }
    }

    /** ◀ ▶ in the channel menu: the previous or next group. */
    private fun switchGroup(delta: Int) {
        ensureCategories {
            if (categories.isEmpty()) return@ensureCategories
            val i = categories.indexOfFirst { it.id == menuCategory?.id }
            val next = if (i < 0) (if (delta > 0) 0 else categories.lastIndex) else Math.floorMod(i + delta, categories.size)
            loadGroup(categories[next])
        }
    }

    private fun playFromMenu(i: Int) {
        if (menuItems.getOrNull(i) == null) return
        lastChannel = PlayQueue.index
        PlayQueue.set(menuItems, i, menuCategory?.name ?: PlayQueue.title, pid(), menuCategory)
        showOverlay(Overlay.NONE)
        play(PlayQueue.current ?: return)
    }

    private fun openGuide(item: Item) {
        guideReturnsTo = overlay
        showOverlay(Overlay.GUIDE)
        guide.tab.text = getString(R.string.guide_tab, item.title)
        guide.message.text = getString(R.string.loading)
        guide.message.visibility = View.VISIBLE
        guideAdapter.submit(emptyList())
        val src = source ?: return
        scope.launch {
            val list = epg[key(item)] ?: try { src.epg(item) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            epg[key(item)] = list
            if (overlay != Overlay.GUIDE) return@launch
            guideList = list
            guideAdapter.submit(list)
            guide.message.text = getString(R.string.no_guide)
            guide.message.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            val now = System.currentTimeMillis()
            if (list.isNotEmpty()) guide.list.focusPosition(list.indexOfFirst { it.end > now }.coerceAtLeast(0))
        }
    }

    private fun openGroups() {
        showOverlay(Overlay.GROUPS)
        groups.tab.text = getString(R.string.groups_tab)
        groups.message.text = getString(R.string.loading)
        groups.message.visibility = View.VISIBLE
        ensureCategories {
            if (overlay != Overlay.GROUPS) return@ensureCategories
            groups.message.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
            groupsAdapter.submit(categories)
            groups.list.focusPosition(categories.indexOfFirst { it.id == (menuCategory ?: PlayQueue.category)?.id }.coerceAtLeast(0))
        }
    }

    private fun toggleFavorite(item: Item) {
        val p = pid().ifEmpty { return }
        val added = Store.toggleFavorite(this, p, item)
        Toast.makeText(this, getString(if (added) R.string.fav_added else R.string.fav_removed, item.title), Toast.LENGTH_SHORT).show()
        if (PlayQueue.current?.let(::key) == key(item)) findViewById<View>(R.id.info_star).visibility = if (added) View.VISIBLE else View.INVISIBLE
    }

    // Playback controls.

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
        if (show && !live) {
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

    private fun handleBack(): Boolean = when {
        descPanel.visibility == View.VISIBLE -> { descPanel.visibility = View.GONE; true }
        overlay != Overlay.NONE -> { closeOverlay(); true }
        controls.visibility == View.VISIBLE -> { setControls(false); true }
        infoBar.visibility == View.VISIBLE -> { setInfo(false); true }
        else -> false
    }

    @Deprecated("Used only below Android 13")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        val selected = if (overlay == Overlay.MENU) menuItems.getOrNull(menuSelected) else PlayQueue.current
        when (code) {
            KeyEvent.KEYCODE_PROG_RED -> { selected?.let { toggleFavorite(it); if (overlay == Overlay.MENU) menuAdapter.notifyItemChanged(menuSelected) }; return true }
            KeyEvent.KEYCODE_PROG_GREEN -> { selected?.takeIf { it.kind == Kind.LIVE }?.let { openGuide(it) }; return true }
            KeyEvent.KEYCODE_PROG_YELLOW -> { if (live) openMenu(allChannels()); return true }
            KeyEvent.KEYCODE_PROG_BLUE -> { if (live) openGroups(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { if (overlay == Overlay.NONE) move(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (overlay == Overlay.NONE) move(-1); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { togglePlay(); return true }
            KeyEvent.KEYCODE_INFO -> {
                if (overlay == Overlay.MENU) showDescription(null, selected) else if (live) toggleDescription()
                return true
            }
        }
        if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && live && overlay == Overlay.NONE) {
            typed += (code - KeyEvent.KEYCODE_0).toString()
            flash.text = typed
            flash.visibility = View.VISIBLE
            handler.removeCallbacks(hideFlash)
            handler.postDelayed(hideFlash, 1500)
            handler.removeCallbacks(jumpTyped)
            handler.postDelayed(jumpTyped, 1500)
            return true
        }
        if (error.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        when (overlay) {
            Overlay.MENU -> when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { switchGroup(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { switchGroup(1); return true }
                KeyEvent.KEYCODE_MENU -> { openGroups(); return true }
            }
            Overlay.GUIDE, Overlay.GROUPS -> if (code == KeyEvent.KEYCODE_MENU) { closeOverlay(); return true }
            Overlay.NONE -> Unit
        }
        if (overlay != Overlay.NONE) return super.dispatchKeyEvent(event)
        if (!live) {
            if (controls.visibility == View.VISIBLE) { touch(); return super.dispatchKeyEvent(event) }
            when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { skip(10_000); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { skip(-10_000); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { setControls(true); return true }
                KeyEvent.KEYCODE_MENU -> { showOptions(); return true }
            }
            return super.dispatchKeyEvent(event)
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { move(1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { move(-1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_RIGHT -> { openMenu(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (lastChannel >= 0) go(lastChannel); return true }
            KeyEvent.KEYCODE_MENU -> { showOptions(); return true }
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
        /** Errors no retry can fix on this TV. */
        val FATAL = setOf(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )
    }
}
