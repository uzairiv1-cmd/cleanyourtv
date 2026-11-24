package app.iptvplayer.tv.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.iptvplayer.tv.Category
import app.iptvplayer.tv.Item
import app.iptvplayer.tv.Kind
import app.iptvplayer.tv.Playlist
import app.iptvplayer.tv.R
import app.iptvplayer.tv.Section
import app.iptvplayer.tv.data.Store
import app.iptvplayer.tv.player.PlayQueue
import app.iptvplayer.tv.player.PlayerActivity
import app.iptvplayer.tv.source.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The browse screen: Live TV / Movies / Series tabs, categories on the left, channels or posters on
 * the right. Long-press OK on anything adds it to Favourites.
 */
class MainActivity : Activity() {
    private val scope = MainScope()

    private lateinit var playlistName: TextView
    private lateinit var tabs: Map<Section, TextView>
    private lateinit var filter: EditText
    private lateinit var header: TextView
    private lateinit var categoriesView: RecyclerView
    private lateinit var itemsView: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView

    private val catAdapter = CategoryAdapter(::onCategoryClicked, ::onCategoryFocused)
    private val itemAdapter = ItemAdapter(::onItemClicked, ::onItemLongClicked)

    private var playlist: Playlist? = null
    private var source: Source? = null
    private var section = Section.LIVE
    private var category: Category? = null
    private var openSeries: Item? = null
    private var allItems: List<Item> = emptyList()
    private var shown: List<Item> = emptyList()
    private var loadJob: Job? = null
    private var focusJob: Job? = null
    private var focusItemsWhenLoaded = false
    private var askedForFirst = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playlistName = findViewById(R.id.playlist_name)
        filter = findViewById(R.id.filter)
        header = findViewById(R.id.header)
        categoriesView = findViewById(R.id.categories)
        itemsView = findViewById(R.id.items)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)
        tabs = mapOf(
            Section.LIVE to findViewById<TextView>(R.id.tab_live),
            Section.MOVIES to findViewById<TextView>(R.id.tab_movies),
            Section.SERIES to findViewById<TextView>(R.id.tab_series),
        )
        tabs.forEach { (s, v) -> v.setOnClickListener { if (s != section || openSeries != null) selectSection(s) } }
        findViewById<View>(R.id.btn_playlists).setOnClickListener {
            startActivity(Intent(this, PlaylistsActivity::class.java))
        }

        categoriesView.layoutManager = LinearLayoutManager(this)
        categoriesView.adapter = catAdapter
        itemsView.layoutManager = GridLayoutManager(this, 2)
        itemsView.adapter = itemAdapter
        itemsView.setHasFixedSize(true)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!handleBack()) finish()
            }
        }

        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
    }

    override fun onResume() {
        super.onResume()
        val active = Store.active(this)
        if (active == null) {
            // Back from the first-run form with nothing saved leaves the app instead of asking again.
            if (askedForFirst) { finish(); return }
            askedForFirst = true
            startActivity(Intent(this, PlaylistEditActivity::class.java).putExtra(PlaylistEditActivity.EXTRA_FIRST, true))
            return
        }
        if (active != playlist) {
            playlist = active
            source = Source.create(active)
            playlistName.text = active.name
            selectSection(Section.LIVE)
        } else {
            refreshFavoriteMarks()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun selectSection(s: Section) {
        section = s
        openSeries = null
        category = null
        filter.setText("")
        tabs.forEach { (k, v) -> v.isActivated = k == s }
        catAdapter.submit(emptyList())
        showItems(emptyList())
        loadJob?.cancel()
        loadJob = scope.launch {
            busy(true)
            try {
                val cats = source!!.categories(s)
                val fav = Category(Category.FAVORITES, getString(R.string.favorites))
                catAdapter.submit(listOf(fav) + cats)
                val first = if (favoritesIn(s).isNotEmpty() || cats.isEmpty()) fav else cats.first()
                loadItems(first)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy(false)
                showMessage(e.message ?: getString(R.string.error_generic))
            }
        }
    }

    private fun favoritesIn(s: Section): List<Item> =
        playlist?.let { p -> Store.favorites(this, p.id).filter { it.section == s } }.orEmpty()

    private fun loadItems(c: Category) {
        category = c
        openSeries = null
        catAdapter.selectedId = c.id
        loadJob?.cancel()
        loadJob = scope.launch {
            busy(true)
            try {
                val list = if (c.id == Category.FAVORITES) favoritesIn(section) else source!!.items(section, c)
                busy(false)
                showItems(list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy(false)
                showItems(emptyList())
                showMessage(e.message ?: getString(R.string.error_generic))
            }
        }
    }

    private fun openSeries(series: Item) {
        loadJob?.cancel()
        loadJob = scope.launch {
            busy(true)
            try {
                val eps = source!!.episodes(series)
                busy(false)
                openSeries = series
                focusItemsWhenLoaded = true
                filter.setText("")
                showItems(eps)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy(false)
                Toast.makeText(this@MainActivity, e.message ?: getString(R.string.error_generic), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showItems(list: List<Item>) {
        allItems = list
        applyFilter()
    }

    private fun applyFilter() {
        val q = filter.text.toString().trim()
        shown = if (q.isEmpty()) allItems else allItems.filter { it.title.contains(q, ignoreCase = true) }
        val posters = section != Section.LIVE && openSeries == null
        (itemsView.layoutManager as GridLayoutManager).spanCount = when {
            posters -> 5
            section == Section.LIVE -> 2
            else -> 1
        }
        refreshFavoriteMarks(notify = false)
        itemAdapter.submit(shown, posters)
        itemsView.scrollToPosition(0)
        header.text = buildString {
            append(openSeries?.title ?: category?.name ?: "")
            if (shown.isNotEmpty()) append("  ·  ").append(shown.size)
        }
        when {
            progress.visibility == View.VISIBLE -> message.visibility = View.GONE
            shown.isEmpty() && category != null -> showMessage(
                when {
                    q.isNotEmpty() -> getString(R.string.no_match, q)
                    category?.id == Category.FAVORITES && openSeries == null -> getString(R.string.no_favorites)
                    else -> getString(R.string.nothing_here)
                }
            )
            else -> message.visibility = View.GONE
        }
        if (focusItemsWhenLoaded && shown.isNotEmpty()) {
            focusItemsWhenLoaded = false
            itemsView.post { itemsView.layoutManager?.findViewByPosition(0)?.requestFocus() }
        }
    }

    private fun refreshFavoriteMarks(notify: Boolean = true) {
        val p = playlist ?: return
        itemAdapter.favoriteIds = Store.favorites(this, p.id).map { it.kind.name + ":" + it.id }.toSet()
        if (notify) itemAdapter.notifyItemRangeChanged(0, itemAdapter.itemCount)
    }

    private fun busy(on: Boolean) {
        progress.visibility = if (on) View.VISIBLE else View.GONE
        if (on) message.visibility = View.GONE
    }

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun onCategoryClicked(c: Category) {
        focusItemsWhenLoaded = true
        if (c.id != category?.id || openSeries != null) loadItems(c) else applyFilter()
    }

    /** Moving through categories loads each one after a short pause, so scrolling past does not fetch them all. */
    private fun onCategoryFocused(c: Category) {
        focusJob?.cancel()
        if (c.id == category?.id) return
        focusJob = scope.launch {
            delay(450)
            if (c.id != category?.id) loadItems(c)
        }
    }

    private fun onItemClicked(position: Int) {
        val item = shown.getOrNull(position) ?: return
        if (item.kind == Kind.SERIES) {
            openSeries(item)
            return
        }
        val playable = shown.filter { it.url != null }
        val start = playable.indexOf(item)
        if (start < 0) return
        PlayQueue.set(playable, start)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun onItemLongClicked(position: Int) {
        val p = playlist ?: return
        val item = shown.getOrNull(position) ?: return
        val added = Store.toggleFavorite(this, p.id, item)
        Toast.makeText(this, getString(if (added) R.string.fav_added else R.string.fav_removed, item.title), Toast.LENGTH_SHORT).show()
        if (category?.id == Category.FAVORITES && openSeries == null) {
            showItems(favoritesIn(section))
        } else {
            refreshFavoriteMarks()
        }
    }

    /** Back steps out of a series, then from the list to the categories, then clears the filter. False: leave the app. */
    private fun handleBack(): Boolean {
        when {
            openSeries != null -> {
                openSeries = null
                category?.let { focusItemsWhenLoaded = true; loadItems(it) }
            }
            itemsView.hasFocus() -> {
                val i = catAdapter.indexOfSelected().coerceAtLeast(0)
                categoriesView.scrollToPosition(i)
                categoriesView.post { categoriesView.layoutManager?.findViewByPosition(i)?.requestFocus() }
            }
            filter.text.isNotEmpty() -> filter.setText("")
            else -> return false
        }
        return true
    }

    // Android 13 and newer deliver Back through the callback registered in onCreate (Android 16
    // no longer calls onBackPressed for apps that target it); older versions call this.
    @Deprecated("Used only below Android 13")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }
}
