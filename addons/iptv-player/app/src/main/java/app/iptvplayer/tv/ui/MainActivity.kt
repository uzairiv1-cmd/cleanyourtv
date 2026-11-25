package app.iptvplayer.tv.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import app.iptvplayer.tv.Item
import app.iptvplayer.tv.Playlist
import app.iptvplayer.tv.R
import app.iptvplayer.tv.Section
import app.iptvplayer.tv.data.Session
import app.iptvplayer.tv.data.Store
import app.iptvplayer.tv.Category
import app.iptvplayer.tv.player.PlayQueue
import app.iptvplayer.tv.player.PlayerActivity
import app.iptvplayer.tv.source.Source
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The main window: the side menu with the AV mark, and one page at a time beside it
 * (Home, Live TV, Movies, Series, Search, Settings). A series opens on top of its page;
 * Back returns. Playback opens [PlayerActivity].
 */
class MainActivity : Activity() {
    enum class Tab { HOME, LIVE, MOVIES, SERIES, SEARCH, SETTINGS }

    val scope = MainScope()
    lateinit var playlist: Playlist
        private set
    lateinit var source: Source
        private set

    private lateinit var content: FrameLayout
    private lateinit var rail: Map<Tab, ImageView>
    private lateinit var splash: View
    private lateinit var clock: TextView
    private var tab = Tab.HOME
    private val pages = HashMap<Tab, Page>()
    private val stack = ArrayList<Page>()
    private var askedForFirst = false
    private var splashJob: Job? = null
    private var splashShownAt = 0L
    /** Opening the app goes straight into live TV once; coming back from the player shows Home. */
    private var liveStarted = false

    private val current: Page? get() = stack.lastOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).background = GlowBackground()
        content = findViewById(R.id.content)
        splash = findViewById(R.id.splash)
        splash.background = GlowBackground()
        clock = findViewById(R.id.clock)
        rail = mapOf(
            Tab.HOME to findViewById(R.id.rail_home),
            Tab.LIVE to findViewById(R.id.rail_live),
            Tab.MOVIES to findViewById(R.id.rail_movies),
            Tab.SERIES to findViewById(R.id.rail_series),
            Tab.SEARCH to findViewById(R.id.rail_search),
            Tab.SETTINGS to findViewById(R.id.rail_settings),
        )
        rail.forEach { (t, v) ->
            v.setOnClickListener { show(t, focusContent = true) }
            v.zoomOnFocus(1.08f) { tintRail() }
        }
        splashShownAt = System.currentTimeMillis()
        animateSplash()

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!handleBack()) finish()
            }
        }
        scope.launch {
            while (true) {
                clock.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                delay(15_000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val active = Store.active(this)
        if (active == null) {
            // Back from the first-run form with nothing saved leaves the app instead of asking again.
            if (askedForFirst) { finish(); return }
            askedForFirst = true
            hideSplash(now = true)
            startActivity(Intent(this, PlaylistEditActivity::class.java).putExtra(PlaylistEditActivity.EXTRA_FIRST, true))
            return
        }
        if (!this::playlist.isInitialized || active != playlist) {
            playlist = active
            source = Session.source(this) ?: Source.create(active)
            pages.values.forEach { it.onHidden() }
            pages.clear()
            stack.clear()
            content.removeAllViews()
            show(Tab.HOME, focusContent = true)
            if (!liveStarted && Store.startInLive(this)) startLive()
            liveStarted = true
        } else {
            current?.onShown()
        }
    }

    override fun onStop() {
        current?.onHidden()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun pageFor(t: Tab): Page = pages.getOrPut(t) {
        when (t) {
            Tab.HOME -> HomePage(this)
            Tab.LIVE -> LivePage(this)
            Tab.MOVIES -> LibraryPage(this, Section.MOVIES)
            Tab.SERIES -> LibraryPage(this, Section.SERIES)
            Tab.SEARCH -> SearchPage(this)
            Tab.SETTINGS -> SettingsPage(this)
        }
    }

    fun show(t: Tab, focusContent: Boolean = false) {
        val page = pageFor(t)
        if (t == tab && stack.size == 1 && stack[0] === page) {
            if (focusContent) page.focusContent()
            return
        }
        current?.onHidden()
        tab = t
        stack.clear()
        stack.add(page)
        attach(page, focusContent)
        tintRail()
    }

    /** Opens a page on top of the current one (a series' episodes); Back returns. */
    fun push(page: Page) {
        current?.onHidden()
        stack.add(page)
        attach(page, focusContent = true)
    }

    private fun attach(page: Page, focusContent: Boolean) {
        content.removeAllViews()
        (page.view.parent as? FrameLayout)?.removeView(page.view)
        content.addView(page.view)
        page.onShown()
        if (focusContent) page.view.post { page.focusContent() }
    }

    private fun tintRail() {
        rail.forEach { (t, v) ->
            v.isActivated = t == tab
            v.imageTintList = ColorStateList.valueOf(
                when {
                    v.isFocused -> Palette.TEXT
                    t == tab -> Palette.ORANGE_LIGHT
                    else -> 0x8CFFFFFF.toInt()
                }
            )
        }
    }

    /** Back: out of a series, then the page's own step, then to the side menu, then out of the app. */
    private fun handleBack(): Boolean {
        val page = current ?: return false
        if (page.onBack()) return true
        if (stack.size > 1) {
            page.onHidden()
            stack.removeAt(stack.lastIndex)
            attach(stack.last(), focusContent = true)
            return true
        }
        if (rail.values.none { it.hasFocus() }) {
            rail[tab]?.requestFocus()
            return true
        }
        if (tab != Tab.HOME) {
            show(Tab.HOME)
            rail[Tab.HOME]?.requestFocus()
            return true
        }
        return false
    }

    @Deprecated("Used only below Android 13")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (splash.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) hideSplash(now = true)
        return super.dispatchKeyEvent(event)
    }

    fun play(items: List<Item>, index: Int, title: String, category: Category? = null) {
        val playable = items.filter { it.url != null }
        val start = playable.indexOf(items.getOrNull(index))
        if (start < 0) return
        current?.onHidden()
        PlayQueue.set(playable, start, title, playlist.id, category)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    fun openSeries(series: Item) = push(EpisodesPage(this, series))

    /** The last live channel in its group (or the first channel of the first group), full screen. */
    private fun startLive() {
        val pid = playlist.id
        val src = source
        scope.launch {
            try {
                val last = Store.lastLive(this@MainActivity, pid)
                val favorites = Category(Category.FAVORITES, getString(R.string.favorites))
                val cats = src.categories(Section.LIVE)
                val cat = when {
                    last?.categoryId == Category.FAVORITES -> favorites
                    last != null -> cats.firstOrNull { it.id == last.categoryId }
                    else -> null
                } ?: cats.firstOrNull { it.id != Category.ALL } ?: return@launch
                val items = if (cat.id == Category.FAVORITES) {
                    Store.favorites(this@MainActivity, pid).filter { it.kind == app.iptvplayer.tv.Kind.LIVE }
                } else {
                    src.items(Section.LIVE, cat)
                }
                if (items.isEmpty()) return@launch
                val index = items.indexOfFirst { it.kind.name + ":" + it.id == last?.itemKey }.coerceAtLeast(0)
                play(items, index, cat.name, cat)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // No live channels reachable: stay on Home.
            }
        }
    }

    fun toggleFavorite(item: Item): Boolean {
        val added = Store.toggleFavorite(this, playlist.id, item)
        android.widget.Toast.makeText(
            this, getString(if (added) R.string.fav_added else R.string.fav_removed, item.title), android.widget.Toast.LENGTH_SHORT,
        ).show()
        return added
    }

    fun isFavorite(item: Item) = Store.isFavorite(this, playlist.id, item)

    // Start screen: the AV mark while the first lists load, at least a moment and at most a few seconds.

    private fun animateSplash() {
        val bar = findViewById<ProgressBar>(R.id.splash_progress)
        splashJob = scope.launch {
            for (p in 0..100 step 4) {
                bar.progress = p
                delay(60)
            }
            hideSplash(now = true)
        }
    }

    /** Called by Home once its first content is on screen. */
    fun contentReady() {
        val wait = 1100 - (System.currentTimeMillis() - splashShownAt)
        scope.launch {
            if (wait > 0) delay(wait)
            hideSplash(now = false)
        }
    }

    private fun hideSplash(now: Boolean) {
        if (splash.visibility != View.VISIBLE) return
        splashJob?.cancel()
        if (now) {
            splash.visibility = View.GONE
        } else {
            splash.animate().alpha(0f).setDuration(350).withEndAction { splash.visibility = View.GONE }.start()
        }
        current?.view?.post { current?.focusContent() }
    }
}
