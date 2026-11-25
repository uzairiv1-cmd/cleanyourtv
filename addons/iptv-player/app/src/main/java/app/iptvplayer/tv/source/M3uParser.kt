package app.iptvplayer.tv.source

import app.iptvplayer.tv.Item
import app.iptvplayer.tv.Kind
import java.io.BufferedReader

/**
 * Reads an M3U / M3U8 playlist line by line, so a playlist of tens of megabytes never sits in
 * memory as one string. Understands `#EXTINF` with `tvg-name`, `tvg-logo`, `group-title`, and `#EXTGRP`.
 */
object M3uParser {
    data class Entry(val title: String, val logo: String?, val group: String, val url: String, val kind: Kind, val epgId: String = "")

    /** The entries, and the TV guide address from the `#EXTM3U x-tvg-url="…"` (or `url-tvg`) header, if any. */
    data class Parsed(val entries: List<Entry>, val epgUrl: String?)

    private val ATTR = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")
    private val VOD_EXT = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "mpg", "mpeg")

    fun parse(reader: BufferedReader, limit: Int = 300_000): List<Entry> = parseFull(reader, limit).entries

    fun parseFull(reader: BufferedReader, limit: Int = 300_000): Parsed {
        var epgUrl: String? = null
        var tvgId = ""
        val out = ArrayList<Entry>()
        var title: String? = null
        var logo: String? = null
        var group = ""
        var extGroup = ""
        while (out.size < limit) {
            val raw = reader.readLine() ?: break
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val (attrs, name) = splitExtinf(line)
                    val map = HashMap<String, String>()
                    ATTR.findAll(attrs).forEach { map[it.groupValues[1].lowercase()] = it.groupValues[2].trim() }
                    title = name.ifBlank { map["tvg-name"].orEmpty() }
                    logo = map["tvg-logo"]?.takeIf { it.isNotBlank() }
                    group = map["group-title"].orEmpty()
                    tvgId = map["tvg-id"].orEmpty()
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substring(8).trim()
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val map = HashMap<String, String>()
                    ATTR.findAll(line).forEach { map[it.groupValues[1].lowercase()] = it.groupValues[2].trim() }
                    epgUrl = (map["x-tvg-url"] ?: map["url-tvg"])?.split(',')?.firstOrNull()?.trim()?.takeIf { it.startsWith("http") }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val url = line
                    if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                        val g = group.ifBlank { extGroup }.ifBlank { "Uncategorised" }
                        val t = title?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/').substringBefore('?')
                        out.add(Entry(t, logo, g, url, kindOf(url), tvgId))
                    }
                    title = null; logo = null; group = ""; extGroup = ""; tvgId = ""
                }
            }
        }
        return Parsed(out, epgUrl)
    }

    /** `#EXTINF:-1 tvg-name="a, b" group-title="x",Channel` -> attributes part and the name after the first comma outside quotes. */
    internal fun splitExtinf(line: String): Pair<String, String> {
        var quoted = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') quoted = !quoted
            else if (c == ',' && !quoted) return line.substring(0, i) to line.substring(i + 1).trim()
        }
        return line to ""
    }

    /** Xtream-style paths say what an entry is; otherwise a file extension marks a movie. */
    internal fun kindOf(url: String): Kind {
        val path = url.substringBefore('?').lowercase()
        return when {
            "/series/" in path -> Kind.EPISODE
            "/movie/" in path -> Kind.MOVIE
            path.substringAfterLast('.', "") in VOD_EXT -> Kind.MOVIE
            else -> Kind.LIVE
        }
    }

    fun toItem(e: Entry): Item = Item(id = e.url, title = e.title, logo = e.logo, url = e.url, kind = e.kind, epgId = e.epgId.ifEmpty { e.title })
}
