package app.iptvplayer.tv.source

import app.iptvplayer.tv.Category
import app.iptvplayer.tv.Item
import app.iptvplayer.tv.Kind
import app.iptvplayer.tv.Playlist
import app.iptvplayer.tv.Section
import app.iptvplayer.tv.SourceType
import app.iptvplayer.tv.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the browse screen asks of a playlist, whatever its kind. Every call may throw an [IOException] with a readable message. */
interface Source {
    suspend fun categories(section: Section): List<Category>
    suspend fun items(section: Section, category: Category): List<Item>
    suspend fun episodes(series: Item): List<Item>

    /** Everything in a section, for search. */
    suspend fun all(section: Section): List<Item> = items(section, Category(Category.ALL, ""))

    companion object {
        fun create(p: Playlist): Source = when (p.type) {
            SourceType.XTREAM -> XtreamSource(XtreamApi(p.server, p.username, p.password))
            SourceType.M3U -> M3uSource(p.url)
        }
    }
}

/** Loads the whole playlist once, then groups it by `group-title` into the three sections. */
class M3uSource(private val url: String) : Source {
    private val mutex = Mutex()
    private var groups: Map<Section, LinkedHashMap<String, MutableList<Item>>>? = null

    private suspend fun load() = mutex.withLock {
        groups ?: withContext(Dispatchers.IO) {
            val entries = try {
                Http.open(url).bufferedReader(Charsets.UTF_8).use { M3uParser.parse(it) }
            } catch (e: IOException) {
                throw Http.friendly(e)
            }
            if (entries.isEmpty()) throw Http.HttpError("The playlist has no channels. Is it an M3U link?")
            val map = Section.entries.associateWith { LinkedHashMap<String, MutableList<Item>>() }
            for (e in entries) {
                val item = M3uParser.toItem(e)
                map.getValue(item.section).getOrPut(e.group) { ArrayList() }.add(item)
            }
            map
        }.also { groups = it }
    }

    override suspend fun categories(section: Section): List<Category> {
        val g = load().getValue(section)
        val cats = g.keys.map { Category(it, "$it (${g.getValue(it).size})") }
        return if (cats.size > 1) listOf(Category(Category.ALL, "All (${g.values.sumOf { it.size }})")) + cats else cats
    }

    override suspend fun items(section: Section, category: Category): List<Item> {
        val g = load().getValue(section)
        return if (category.id == Category.ALL) g.values.flatten() else g[category.id].orEmpty()
    }

    // In an M3U list, episodes are already playable entries.
    override suspend fun episodes(series: Item): List<Item> = emptyList()
}

/** Asks the panel per category, so even very large accounts load quickly; answers are kept for the session. */
class XtreamSource(private val api: XtreamApi) : Source {
    private val cache = HashMap<String, Any>()
    private val mutex = Mutex()
    private var liveFormat: String? = null

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        try { Http.text(url) } catch (e: IOException) { throw Http.friendly(e) }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> cached(key: String, block: suspend () -> T): T = mutex.withLock {
        (cache[key] as T?) ?: block().also { cache[key] = it as Any }
    }

    /** HLS (.m3u8) when the account allows it, as Media3 handles a live HLS stream best; MPEG-TS otherwise. */
    private suspend fun liveExt(): String = liveFormat ?: run {
        val info = try { XtreamParser.auth(get(api.api())) } catch (e: IOException) { null }
        if (info != null && !info.ok) throw Http.HttpError(info.message)
        val f = info?.formats.orEmpty()
        (if (f.isEmpty() || "m3u8" in f) "m3u8" else "ts").also { liveFormat = it }
    }

    override suspend fun categories(section: Section): List<Category> = cached("cat_$section") {
        val action = when (section) {
            Section.LIVE -> "get_live_categories"
            Section.MOVIES -> "get_vod_categories"
            Section.SERIES -> "get_series_categories"
        }
        if (section == Section.LIVE) liveExt() // checks the login once, with a clear message
        val text = get(api.api(action))
        val parsed = withContext(Dispatchers.Default) { XtreamParser.categories(text) }
        // Every channel in one list, for browsing and searching them all.
        val cats = if (section == Section.LIVE && parsed.isNotEmpty()) listOf(Category(Category.ALL, "All channels")) + parsed else parsed
        if (parsed.isEmpty() && text.trim().let { it.isEmpty() || it.startsWith("<") }) {
            throw Http.HttpError("The server did not answer like an Xtream Codes panel")
        }
        cats
    }

    override suspend fun items(section: Section, category: Category): List<Item> = cached("items_${section}_${category.id}") {
        val params = if (category.id == Category.ALL) emptyArray<Pair<String, String>>() else arrayOf("category_id" to category.id)
        when (section) {
            Section.LIVE -> {
                val ext = liveExt()
                val text = get(api.api("get_live_streams", *params))
                withContext(Dispatchers.Default) { XtreamParser.live(text, api, ext) }
            }
            Section.MOVIES -> {
                val text = get(api.api("get_vod_streams", *params))
                withContext(Dispatchers.Default) { XtreamParser.movies(text, api) }
            }
            Section.SERIES -> {
                val text = get(api.api("get_series", *params))
                withContext(Dispatchers.Default) { XtreamParser.series(text) }
            }
        }
    }

    override suspend fun episodes(series: Item): List<Item> = cached("series_${series.id}") {
        if (series.kind != Kind.SERIES) return@cached emptyList<Item>()
        val text = get(api.api("get_series_info", "series_id" to series.id))
        withContext(Dispatchers.Default) { XtreamParser.episodes(text, api, series.logo) }
    }
}
