package app.iptvplayer.tv.ui

import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.iptvplayer.tv.Category
import app.iptvplayer.tv.Item
import app.iptvplayer.tv.Kind
import app.iptvplayer.tv.R
import app.iptvplayer.tv.Section
import app.iptvplayer.tv.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Movies or Series: category chips across the top, a wall of posters below. */
class LibraryPage(host: MainActivity, private val section: Section) : Page(host, R.layout.page_library) {
    private val chipsView: RecyclerView = find(R.id.chips)
    private val grid: RecyclerView = find(R.id.grid)
    private var categories: List<Category> = emptyList()
    private var selected: Category? = null
    private var items: List<Item> = emptyList()
    private var loaded = false
    private var lastFocused = 0
    private var loadJob: Job? = null
    private var focusJob: Job? = null

    private val chipAdapter: SimpleAdapter<Category> = SimpleAdapter<Category>(R.layout.item_chip) { v, c, _ ->
        (v as TextView).text = c.name
        v.isActivated = c.id == selected?.id
        v.setOnClickListener { if (c.id != selected?.id) load(c, focusGrid = true) else grid.focusPosition(0) }
        v.zoomOnFocus(1.06f) { has -> if (has) onChipFocused(c) }
    }

    private val posterAdapter: SimpleAdapter<Item> = SimpleAdapter<Item>(R.layout.item_poster) { v, item, i ->
        bindPoster(v, item, host.isFavorite(item))
        val card = v.findViewById<View>(R.id.card)
        card.setOnClickListener { open(i) }
        card.setOnLongClickListener { host.toggleFavorite(item); refresh(i); true }
        card.zoomOnFocus(1.07f) { has -> if (has) lastFocused = i }
    }

    init {
        find<TextView>(R.id.title).setText(if (section == Section.MOVIES) R.string.tab_movies else R.string.tab_series)
        chipsView.layoutManager = LinearLayoutManager(host, LinearLayoutManager.HORIZONTAL, false)
        chipsView.adapter = chipAdapter
        grid.layoutManager = GridLayoutManager(host, 7)
        grid.adapter = posterAdapter
        grid.itemAnimator = null
    }

    override fun onShown() {
        if (!loaded) loadCategories() else posterAdapter.notifyItemRangeChanged(0, posterAdapter.itemCount)
    }

    override fun focusContent() {
        if (items.isNotEmpty()) grid.focusPosition(lastFocused.coerceAtMost(items.lastIndex))
        else chipsView.focusPosition(categories.indexOf(selected).coerceAtLeast(0))
    }

    override fun onBack(): Boolean {
        if (grid.hasFocus()) {
            chipsView.focusPosition(categories.indexOf(selected).coerceAtLeast(0))
            return true
        }
        return false
    }

    private fun loadCategories() {
        loaded = true
        busy(true)
        host.scope.launch {
            try {
                val cats = host.source.categories(section)
                val fav = Category(Category.FAVORITES, host.getString(R.string.favorites))
                categories = listOf(fav) + cats
                chipAdapter.submit(categories)
                val hasFav = Store.favorites(host, host.playlist.id).any { it.section == section }
                load(if (hasFav || cats.isEmpty()) fav else cats.first(), focusGrid = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loaded = false
                busy(false)
                showMessage(e.message)
            }
        }
    }

    private fun onChipFocused(c: Category) {
        focusJob?.cancel()
        if (c.id == selected?.id) return
        focusJob = host.scope.launch {
            delay(450)
            load(c, focusGrid = false)
        }
    }

    private fun load(c: Category, focusGrid: Boolean) {
        val old = categories.indexOf(selected)
        selected = c
        if (old >= 0) chipAdapter.notifyItemChanged(old)
        categories.indexOf(c).takeIf { it >= 0 }?.let { chipAdapter.notifyItemChanged(it) }
        loadJob?.cancel()
        loadJob = host.scope.launch {
            busy(true)
            showMessage(null)
            try {
                items = if (c.id == Category.FAVORITES) {
                    Store.favorites(host, host.playlist.id).filter { it.section == section }
                } else {
                    host.source.items(section, c)
                }
                busy(false)
                lastFocused = 0
                posterAdapter.submit(items)
                grid.scrollToPosition(0)
                if (items.isEmpty()) {
                    showMessage(host.getString(if (c.id == Category.FAVORITES) R.string.no_favorites else R.string.nothing_here))
                }
                if (focusGrid && items.isNotEmpty()) grid.focusPosition(0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy(false)
                posterAdapter.submit(emptyList())
                showMessage(e.message)
            }
        }
    }

    private fun refresh(i: Int) = posterAdapter.notifyItemChanged(i)

    private fun open(i: Int) {
        val item = items.getOrNull(i) ?: return
        lastFocused = i
        if (item.kind == Kind.SERIES) host.openSeries(item) else host.play(items, i, selected?.name.orEmpty())
    }
}

/** A series: its poster and name on the left, the episodes on the right. */
class EpisodesPage(host: MainActivity, private val series: Item) : Page(host, R.layout.page_episodes) {
    private val list: RecyclerView = find(R.id.list)
    private var episodes: List<Item> = emptyList()
    private var loaded = false
    private var lastFocused = 0

    private val adapter: SimpleAdapter<Item> = SimpleAdapter<Item>(R.layout.item_episode) { v, ep, i ->
        val img = v.findViewById<ImageView>(R.id.image)
        img.clipToBackground()
        ImageLoader.load(img, ep.logo, MonogramDrawable(series.title), 240)
        v.findViewById<TextView>(R.id.title).text = ep.title
        v.findViewById<TextView>(R.id.info).text = ep.info
        val p = ep.url?.let { Store.progress(host, it) }
        v.findViewById<ProgressBar>(R.id.progress).apply {
            visibility = if (p != null) View.VISIBLE else View.GONE
            progress = ((p ?: 0f) * 1000).toInt()
        }
        v.setOnClickListener { lastFocused = i; host.play(episodes, i, series.title) }
        v.zoomOnFocus(1.03f) { has -> if (has) lastFocused = i }
    }

    init {
        val poster = find<ImageView>(R.id.poster)
        poster.clipToBackground()
        ImageLoader.load(poster, series.logo, MonogramDrawable(series.title), 420)
        find<TextView>(R.id.title).text = series.title
        find<TextView>(R.id.info).text = series.info
        list.layoutManager = LinearLayoutManager(host)
        list.adapter = adapter
        find<View>(R.id.btn_fav).apply {
            setOnClickListener { host.toggleFavorite(series); updateFav() }
            zoomOnFocus(1.06f)
        }
        updateFav()
    }

    private fun updateFav() {
        find<TextView>(R.id.btn_fav).setText(if (host.isFavorite(series)) R.string.unfavourite else R.string.favourite)
    }

    override fun onShown() {
        if (loaded) { adapter.notifyItemRangeChanged(0, adapter.itemCount); return }
        loaded = true
        busy(true)
        host.scope.launch {
            try {
                episodes = host.source.episodes(series)
                busy(false)
                adapter.submit(episodes)
                if (episodes.isEmpty()) showMessage(host.getString(R.string.nothing_here))
                else {
                    find<TextView>(R.id.info).text = listOf(series.info, host.getString(R.string.episodes_count, episodes.size))
                        .filter { it.isNotBlank() }.joinToString("  ·  ")
                    list.focusPosition(0)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loaded = false
                busy(false)
                showMessage(e.message)
            }
        }
    }

    override fun focusContent() {
        if (episodes.isNotEmpty()) list.focusPosition(lastFocused) else find<View>(R.id.btn_fav).requestFocus()
    }
}
