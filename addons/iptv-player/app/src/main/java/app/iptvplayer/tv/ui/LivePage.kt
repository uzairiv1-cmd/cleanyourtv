package app.iptvplayer.tv.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.iptvplayer.tv.Category
import app.iptvplayer.tv.Item
import app.iptvplayer.tv.R
import app.iptvplayer.tv.Section
import app.iptvplayer.tv.data.Store
import app.iptvplayer.tv.player.Players
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live TV: categories on the left, numbered channels in the middle, and on the right a muted preview
 * of the focused channel with its name and Watch / Favourite.
 */
class LivePage(host: MainActivity) : Page(host, R.layout.page_live) {
    private val categoriesView: RecyclerView = find(R.id.categories)
    private val channelsView: RecyclerView = find(R.id.channels)
    private val previewView: PlayerView = find(R.id.preview)
    private val previewLogo: ImageView = find(R.id.preview_logo)

    private var categories: List<Category> = emptyList()
    private var selected: Category? = null
    private var channels: List<Item> = emptyList()
    private var focused = 0
    private var loadJob: Job? = null
    private var focusJob: Job? = null
    private var previewJob: Job? = null
    private var preview: ExoPlayer? = null
    private var loaded = false

    private val catAdapter: SimpleAdapter<Category> = SimpleAdapter<Category>(R.layout.item_category) { v, c, _ ->
        (v as TextView).text = c.name
        v.isActivated = c.id == selected?.id
        v.setOnClickListener {
            if (c.id != selected?.id) load(c, focusList = true) else channelsView.focusPosition(0)
        }
        v.zoomOnFocus(1.03f) { has -> if (has) onCategoryFocused(c) }
    }

    private val chAdapter: SimpleAdapter<Item> = SimpleAdapter<Item>(R.layout.item_channel) { v, item, i ->
        v.findViewById<TextView>(R.id.number).text = (i + 1).toString()
        val img = v.findViewById<ImageView>(R.id.image)
        img.clipToBackground()
        ImageLoader.load(img, item.logo, MonogramDrawable(item.title), 120)
        v.findViewById<TextView>(R.id.title).text = item.title
        v.findViewById<View>(R.id.star).visibility = if (host.isFavorite(item)) View.VISIBLE else View.GONE
        v.setOnClickListener { watch(i) }
        v.setOnLongClickListener { host.toggleFavorite(item); refreshRow(i); updateInfo(); true }
        v.zoomOnFocus(1.03f) { has -> if (has) onChannelFocused(i) }
    }

    init {
        categoriesView.layoutManager = LinearLayoutManager(host)
        categoriesView.adapter = catAdapter
        channelsView.layoutManager = LinearLayoutManager(host)
        channelsView.adapter = chAdapter
        channelsView.itemAnimator = null
        find<TextView>(R.id.hints).setText(R.string.live_hints)
        find<View>(R.id.btn_watch).apply { setOnClickListener { watch(focused) }; zoomOnFocus(1.06f) }
        find<View>(R.id.btn_fav).apply {
            setOnClickListener { channels.getOrNull(focused)?.let { host.toggleFavorite(it); refreshRow(focused); updateInfo() } }
            zoomOnFocus(1.06f)
        }
        updateInfo()
    }

    override fun onShown() {
        if (!loaded) loadCategories() else chAdapter.notifyItemRangeChanged(0, chAdapter.itemCount)
    }

    override fun onHidden() {
        stopPreview()
    }

    override fun focusContent() {
        if (channels.isNotEmpty()) channelsView.focusPosition(focused) else categoriesView.focusPosition(categories.indexOf(selected).coerceAtLeast(0))
    }

    override fun onBack(): Boolean {
        if (channelsView.hasFocus() || find<View>(R.id.btn_watch).hasFocus() || find<View>(R.id.btn_fav).hasFocus()) {
            categoriesView.focusPosition(categories.indexOf(selected).coerceAtLeast(0))
            return true
        }
        return false
    }

    private fun loadCategories() {
        loaded = true
        busy(true)
        host.scope.launch {
            try {
                val cats = host.source.categories(Section.LIVE)
                val fav = Category(Category.FAVORITES, "★  " + host.getString(R.string.favorites).removePrefix("★ ").trim())
                categories = listOf(fav) + cats
                catAdapter.submit(categories)
                val hasFav = Store.favorites(host, host.playlist.id).any { it.section == Section.LIVE }
                // Start in Favourites, or the first real category rather than the (large) All channels list.
                val first = when {
                    hasFav -> fav
                    cats.size > 1 && cats[0].id == Category.ALL -> cats[1]
                    else -> cats.firstOrNull() ?: fav
                }
                load(first, focusList = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loaded = false
                busy(false)
                showMessage(e.message)
            }
        }
    }

    private fun onCategoryFocused(c: Category) {
        focusJob?.cancel()
        if (c.id == selected?.id) return
        focusJob = host.scope.launch {
            delay(450)
            load(c, focusList = false)
        }
    }

    private fun load(c: Category, focusList: Boolean) {
        val old = categories.indexOf(selected)
        selected = c
        if (old >= 0) catAdapter.notifyItemChanged(old)
        categories.indexOf(c).takeIf { it >= 0 }?.let { catAdapter.notifyItemChanged(it) }
        loadJob?.cancel()
        loadJob = host.scope.launch {
            busy(true)
            showMessage(null)
            try {
                channels = if (c.id == Category.FAVORITES) {
                    Store.favorites(host, host.playlist.id).filter { it.section == Section.LIVE }
                } else {
                    host.source.items(Section.LIVE, c)
                }
                busy(false)
                chAdapter.submit(channels)
                channelsView.scrollToPosition(0)
                focused = 0
                updateInfo()
                if (channels.isEmpty()) {
                    showMessage(host.getString(if (c.id == Category.FAVORITES) R.string.no_favorites else R.string.nothing_here))
                }
                if (focusList && channels.isNotEmpty()) channelsView.focusPosition(0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy(false)
                chAdapter.submit(emptyList())
                showMessage(e.message)
            }
        }
    }

    private fun onChannelFocused(i: Int) {
        focused = i
        updateInfo()
        stopPreview()
        if (!Store.previewEnabled(host)) return
        previewJob = host.scope.launch {
            delay(800)
            startPreview(channels.getOrNull(i) ?: return@launch)
        }
    }

    private fun updateInfo() {
        val item = channels.getOrNull(focused)
        find<TextView>(R.id.info_cap).text = if (item != null) host.getString(R.string.channel_n, focused + 1) else ""
        find<TextView>(R.id.info_title).text = item?.title ?: selected?.name.orEmpty()
        find<TextView>(R.id.info_sub).text = selected?.name?.removePrefix("★").orEmpty().trim()
        find<TextView>(R.id.btn_fav).setText(if (item != null && host.isFavorite(item)) R.string.unfavourite else R.string.favourite)
        find<View>(R.id.btn_watch).visibility = if (item != null) View.VISIBLE else View.INVISIBLE
        find<View>(R.id.btn_fav).visibility = if (item != null) View.VISIBLE else View.INVISIBLE
        previewLogo.clipToBackground()
        ImageLoader.load(previewLogo, item?.logo, item?.let { MonogramDrawable(it.title) }, 240)
    }

    private fun refreshRow(i: Int) = chAdapter.notifyItemChanged(i)

    private fun watch(i: Int) {
        if (channels.getOrNull(i) == null) return
        stopPreview()
        host.play(channels, i, selected?.name?.removePrefix("★")?.trim().orEmpty(), selected)
    }

    private fun startPreview(item: Item) {
        val media = Players.mediaItem(item) ?: return
        val p = preview ?: Players.create(host, preview = true).also {
            it.volume = 0f
            it.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { previewView.visibility = View.VISIBLE }
                override fun onPlayerError(error: PlaybackException) { previewView.visibility = View.INVISIBLE }
            })
            preview = it
            previewView.player = it
        }
        p.setMediaItem(media)
        p.prepare()
        p.playWhenReady = true
    }

    /** Stops and releases the preview: many IPTV accounts allow one stream at a time. */
    private fun stopPreview() {
        previewJob?.cancel()
        previewView.visibility = View.INVISIBLE
        preview?.let {
            previewView.player = null
            it.release()
        }
        preview = null
    }
}
