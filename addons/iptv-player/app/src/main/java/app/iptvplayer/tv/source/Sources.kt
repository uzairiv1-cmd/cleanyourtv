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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedInputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the browse screen asks of a playlist, whatever its kind. Every call may throw an [IOException] with a readable message. */
interface Source {
    suspend fun categories(section: Section): List<Category>
    suspend fun items(section: Section, category: Category): List<Item>
    suspend fun episodes(series: Item): List<Item>

    /** The TV guide for a live channel, sorted by start; empty when the playlist has none. */
    suspend fun epg(item: Item): List<Programme> = emptyList()

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
    private var epgUrl: String? = null
    private val guideMutex = Mutex()
    private var guide: Map<String, List<Programme>>? = null
    private var guideAt = 0L

    private suspend fun load() = mutex.withLock {
        groups ?: withContext(Dispatchers.IO) {
            val parsed = try {
                Http.open(url).bufferedReader(Charsets.UTF_8).use { M3uParser.parseFull(it) }
            } catch (e: IOException) {
                throw Http.friendly(e)
            }
            epgUrl = parsed.epgUrl
            val entries = parsed.entries
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

    /** The list's XMLTV guide (from its `x-tvg-url`), read once for its own channels, then every 6 hours. */
    override suspend fun epg(item: Item): List<Programme> {
        load()
        val gUrl = epgUrl ?: return emptyList()
        val g = guideMutex.withLock {
            val now = System.currentTimeMillis()
            guide?.takeIf { now - guideAt < 6 * 3600_000L } ?: withContext(Dispatchers.IO) {
                val wanted = load().getValue(Section.LIVE).values.flatten().map { it.epgId }.filter { it.isNotEmpty() }.toSet()
                try {
                    Http.open(gUrl).use { raw ->
                        val input = BufferedInputStream(raw, 64 * 1024)
                        input.mark(2)
                        val gz = input.read() == 0x1f && input.read() == 0x8b
                        input.reset()
                        Epg.parseXmltv(if (gz) GZIPInputStream(input, 64 * 1024) else input, wanted, now - 3 * 3600_000L, now + 36 * 3600_000L)
                    }
                } catch (e: Exception) {
                    emptyMap()
                }
            }.also { guide = it; guideAt = now }
        }
        return g[item.epgId.lowercase()].orEmpty()
    }

    // In an M3U list, episodes are already playable entries.
    override suspend fun episodes(series: Item): List<Item> = emptyList()
}

/** Asks the panel per category, so even very large accounts load quickly; answers are kept for the session. */
class XtreamSource(private val api: XtreamApi) : Source {
    private val cache = HashMap<String, Any>()
    private val epgCache = HashMap<String, Pair<Long, List<Programme>>>()
    private val epgMutex = Mutex()
    private val epgGate = Semaphore(4)
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

    override suspend fun epg(item: Item): List<Programme> {
        if (item.kind != app.iptvplayer.tv.Kind.LIVE) return emptyList()
        val id = item.epgId.ifEmpty { item.id }
        val now = System.currentTimeMillis()
        epgMutex.withLock { epgCache[id]?.takeIf { now - it.first < 15 * 60_000L && (it.second.lastOrNull()?.end ?: 0) > now }?.let { return it.second } }
        val list = epgGate.withPermit {
            try {
                val text = get(api.api("get_short_epg", "stream_id" to id, "limit" to "24"))
                withContext(Dispatchers.Default) { Epg.parseXtream(text) }
            } catch (e: IOException) {
                emptyList()
            }
        }
        epgMutex.withLock { epgCache[id] = now to list }
        return list
    }

    override suspend fun episodes(series: Item): List<Item> = cached("series_${series.id}") {
        if (series.kind != Kind.SERIES) return@cached emptyList<Item>()
        val text = get(api.api("get_series_info", "series_id" to series.id))
        withContext(Dispatchers.Default) { XtreamParser.episodes(text, api, series.logo) }
    }
}
