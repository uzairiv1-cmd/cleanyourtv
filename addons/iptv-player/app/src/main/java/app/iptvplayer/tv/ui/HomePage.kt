package app.iptvplayer.tv.ui

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.iptvplayer.tv.Category
import app.iptvplayer.tv.Item
import app.iptvplayer.tv.Kind
import app.iptvplayer.tv.R
import app.iptvplayer.tv.Section
import app.iptvplayer.tv.data.Store
import kotlinx.coroutines.launch
import java.util.Calendar

/** Home: a greeting, Continue watching, the three doors, favourite channels, history and favourite titles. */
class HomePage(host: MainActivity) : Page(host, R.layout.page_home) {
    private val hero: View = find(R.id.hero)
    private val heroImage: ImageView = find(R.id.hero_image)
    private val rowChannels: RecyclerView = find(R.id.row_channels)
    private val rowRecent: RecyclerView = find(R.id.row_recent)
    private val rowShows: RecyclerView = find(R.id.row_shows)
    private var recents: List<Item> = emptyList()
    private var favChannels: List<Item> = emptyList()
    private var countsLoaded = false

    private val channelsAdapter: SimpleAdapter<Item> = SimpleAdapter<Item>(R.layout.item_circle) { v, item, i ->
        val img = v.findViewById<ImageView>(R.id.image)
        img.clipToBackground()
        ImageLoader.load(img, item.logo, MonogramDrawable(item.title, circle = true), 120)
        v.findViewById<TextView>(R.id.title).text = item.title
        v.setOnClickListener {
            host.play(favChannels, i, host.getString(R.string.favourite_channels), Category(Category.FAVORITES, host.getString(R.string.favorites)))
        }
        v.setOnLongClickListener { host.toggleFavorite(item); refresh(); true }
        v.zoomOnFocus(1.1f)
    }

    private val recentAdapter: SimpleAdapter<Item> = SimpleAdapter<Item>(R.layout.item_wide) { v, item, _ ->
        bindWide(v, item)
        v.setOnClickListener { open(item) }
        v.setOnLongClickListener { host.toggleFavorite(item); refresh(); true }
        v.zoomOnFocus(1.06f)
    }

    private val showsAdapter: SimpleAdapter<Item> = SimpleAdapter<Item>(R.layout.item_poster) { v, item, _ ->
        v.layoutParams.width = host.dp(111)
        bindPoster(v, item, favorite = false)
        val card = v.findViewById<View>(R.id.card)
        card.setOnClickListener { open(item) }
        card.setOnLongClickListener { host.toggleFavorite(item); refresh(); true }
        card.zoomOnFocus(1.06f)
    }

    init {
        find<TextView>(R.id.title).setTitleWithAccent(host.playlist.name)
        for ((row, adapter) in listOf(rowChannels to channelsAdapter, rowRecent to recentAdapter, rowShows to showsAdapter)) {
            row.layoutManager = LinearLayoutManager(host, LinearLayoutManager.HORIZONTAL, false)
            row.adapter = adapter
        }
        door(R.id.door_live, R.drawable.ic_live, R.string.tab_live, MainActivity.Tab.LIVE)
        door(R.id.door_movies, R.drawable.ic_movies, R.string.tab_movies, MainActivity.Tab.MOVIES)
        door(R.id.door_series, R.drawable.ic_series, R.string.tab_series, MainActivity.Tab.SERIES)
        hero.zoomOnFocus(1.02f)
    }

    private fun door(id: Int, icon: Int, title: Int, target: MainActivity.Tab) {
        val d = find<View>(id)
        d.findViewById<ImageView>(R.id.door_icon).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Palette.ORANGE_LIGHT)
        }
        d.findViewById<TextView>(R.id.door_title).setText(title)
        d.findViewById<TextView>(R.id.door_sub).setText(R.string.browse)
        d.setOnClickListener { host.show(target, focusContent = true) }
        d.zoomOnFocus(1.04f)
    }

    override fun onShown() {
        refresh()
        if (!countsLoaded) loadCounts()
    }

    override fun focusContent() {
        hero.requestFocus()
    }

    private fun refresh() {
        val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> R.string.good_morning
            in 12..17 -> R.string.good_afternoon
            else -> R.string.good_evening
        }
        find<TextView>(R.id.greeting).setText(greeting)
        val pid = host.playlist.id
        recents = Store.recents(host, pid)
        val favs = Store.favorites(host, pid)
        favChannels = favs.filter { it.kind == Kind.LIVE }
        val favShows = favs.filter { it.kind != Kind.LIVE }

        bindHero(recents.firstOrNull())
        section(R.id.cap_channels, rowChannels, favChannels.isNotEmpty())
        section(R.id.cap_recent, rowRecent, recents.size > 1)
        section(R.id.cap_shows, rowShows, favShows.isNotEmpty())
        find<View>(R.id.hint).visibility = if (recents.isEmpty() && favs.isEmpty()) View.VISIBLE else View.GONE
        channelsAdapter.submit(favChannels)
        recentAdapter.submit(recents.drop(1))
        showsAdapter.submit(favShows)
    }

    private fun section(cap: Int, row: RecyclerView, visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.GONE
        find<View>(cap).visibility = vis
        row.visibility = vis
    }

    private fun bindHero(item: Item?) {
        val cap = find<TextView>(R.id.hero_cap)
        val title = find<TextView>(R.id.hero_title)
        val info = find<TextView>(R.id.hero_info)
        val bar = find<ProgressBar>(R.id.hero_progress)
        val button = find<TextView>(R.id.hero_button)
        if (item == null) {
            cap.setText(R.string.welcome_cap)
            title.setText(R.string.start_watching)
            info.setText(R.string.start_info)
            bar.visibility = View.GONE
            button.setText(R.string.open_live)
            heroImage.setImageDrawable(null)
            hero.setOnClickListener { host.show(MainActivity.Tab.LIVE, focusContent = true) }
            return
        }
        cap.setText(R.string.continue_watching)
        title.text = item.title
        val progress = item.url?.let { Store.progress(host, it) }
        info.text = when {
            item.kind == Kind.LIVE -> host.getString(R.string.live_channel)
            progress != null -> host.getString(R.string.percent_watched, (progress * 100).toInt())
            else -> item.info
        }
        bar.visibility = if (progress != null) View.VISIBLE else View.GONE
        bar.progress = ((progress ?: 0f) * 1000).toInt()
        button.setText(if (progress != null) R.string.resume else R.string.watch_now)
        heroImage.clipToBackground()
        heroImage.scaleType = if (item.kind == Kind.LIVE) ImageView.ScaleType.FIT_END else ImageView.ScaleType.CENTER_CROP
        heroImage.setPadding(0, 0, if (item.kind == Kind.LIVE) host.dp(40) else 0, 0)
        ImageLoader.load(heroImage, item.logo, null, 640)
        hero.setOnClickListener { open(item) }
        hero.setOnLongClickListener { host.toggleFavorite(item); refresh(); true }
    }

    private fun bindWide(v: View, item: Item) {
        val img = v.findViewById<ImageView>(R.id.image)
        img.clipToBackground()
        img.scaleType = if (item.kind == Kind.LIVE) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        ImageLoader.load(img, item.logo, MonogramDrawable(item.title), 320)
        v.findViewById<TextView>(R.id.title).text = item.title
        v.findViewById<View>(R.id.live_badge).visibility = if (item.kind == Kind.LIVE) View.VISIBLE else View.GONE
        val p = item.url?.let { Store.progress(host, it) }
        v.findViewById<ProgressBar>(R.id.progress).apply {
            visibility = if (p != null) View.VISIBLE else View.GONE
            progress = ((p ?: 0f) * 1000).toInt()
        }
    }

    /** Live opens among the channels in the history, so Up/Down moves between them; anything else on its own. */
    private fun open(item: Item) {
        when (item.kind) {
            Kind.SERIES -> host.openSeries(item)
            Kind.LIVE -> {
                val live = recents.filter { it.kind == Kind.LIVE }
                host.play(live, live.indexOf(item).coerceAtLeast(0), host.getString(R.string.recently_watched))
            }
            else -> host.play(listOf(item), 0, host.getString(R.string.continue_watching))
        }
    }

    /** "24 categories" under each door; the first answer also closes the start screen. */
    private fun loadCounts() {
        countsLoaded = true
        val doors = mapOf(Section.LIVE to R.id.door_live, Section.MOVIES to R.id.door_movies, Section.SERIES to R.id.door_series)
        host.scope.launch {
            for ((section, id) in doors) {
                val n = try { host.source.categories(section).size } catch (e: Exception) { -1 }
                if (n > 0) find<View>(id).findViewById<TextView>(R.id.door_sub).text = host.getString(R.string.categories_count, n)
                if (section == Section.LIVE) host.contentReady()
            }
        }
    }
}

/** Shared by posters everywhere: picture, title, favourite star, progress line and the year or rating below. */
fun bindPoster(v: View, item: Item, favorite: Boolean) {
    val ctx = v.context
    val img = v.findViewById<ImageView>(R.id.image)
    img.clipToBackground()
    ImageLoader.load(img, item.logo, MonogramDrawable(item.title), 300)
    v.findViewById<TextView>(R.id.title).text = item.title
    v.findViewById<View>(R.id.star).visibility = if (favorite) View.VISIBLE else View.GONE
    v.findViewById<TextView>(R.id.info).text = item.info.ifEmpty {
        ctx.getString(if (item.kind == Kind.SERIES) R.string.kind_series else R.string.kind_movie)
    }
    val p = item.url?.let { Store.progress(ctx, it) }
    v.findViewById<ProgressBar>(R.id.progress).apply {
        visibility = if (p != null) View.VISIBLE else View.GONE
        progress = ((p ?: 0f) * 1000).toInt()
    }
}
