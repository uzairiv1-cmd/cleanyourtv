package app.iptvplayer.tv.source

import org.json.JSONException
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone

/** One programme in the TV guide; times in epoch milliseconds. */
data class Programme(val start: Long, val end: Long, val title: String, val description: String = "") {
    fun isOn(now: Long) = now in start until end
    fun progress(now: Long): Float = if (end > start) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
    /** Minutes until it ends, rounded up. */
    fun minutesLeft(now: Long): Int = ((end - now + 59_999) / 60_000).toInt().coerceAtLeast(0)
    val minutes: Int get() = ((end - start) / 60_000).toInt()
}

object Epg {
    /** What is on now and what follows, from a list sorted by start. */
    fun nowNext(list: List<Programme>, now: Long): Pair<Programme?, Programme?> {
        val i = list.indexOfFirst { it.isOn(now) }
        if (i < 0) return null to list.firstOrNull { it.start > now }
        return list[i] to list.getOrNull(i + 1)
    }

    /**
     * Xtream `get_short_epg` / `get_simple_data_table`: `{"epg_listings":[{"title":base64,"description":base64,
     * "start_timestamp":"…","stop_timestamp":"…"}]}`. Some panels send plain text instead of base64.
     */
    fun parseXtream(json: String): List<Programme> {
        val arr = try { JSONObject(json.trim()).optJSONArray("epg_listings") } catch (e: JSONException) { null } ?: return emptyList()
        val out = ArrayList<Programme>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = XtreamParser.s(o, "start_timestamp").toLongOrNull()?.times(1000) ?: continue
            val end = (XtreamParser.s(o, "stop_timestamp").ifEmpty { XtreamParser.s(o, "end_timestamp") }).toLongOrNull()?.times(1000) ?: continue
            if (end <= start) continue
            out.add(Programme(start, end, b64(XtreamParser.s(o, "title")), b64(XtreamParser.s(o, "description"))))
        }
        return out.sortedBy { it.start }.distinctBy { it.start }
    }

    private fun b64(s: String): String {
        if (s.isEmpty()) return s
        return try {
            val text = String(Base64.getDecoder().decode(s), Charsets.UTF_8)
            if (text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) s else text.trim()
        } catch (e: IllegalArgumentException) {
            s
        }
    }

    /** XMLTV time: `20240101120000 +0100` (the offset may be missing: UTC then). */
    fun parseXmltvTime(s: String): Long? {
        val t = s.trim()
        if (t.length < 14) return null
        val base = t.substring(0, 14)
        val zone = t.substring(14).trim().ifEmpty { "+0000" }
        return try {
            SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse("$base $zone")?.time
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Streams an XMLTV guide, keeping only [wanted] channels and programmes that overlap [from]..[to], so a
     * guide of tens of megabytes never sits in memory. Channel ids are compared without case.
     */
    fun parseXmltv(input: InputStream, wanted: Set<String>, from: Long, to: Long): Map<String, List<Programme>> {
        val want = wanted.map { it.lowercase() }.toSet()
        val out = HashMap<String, MutableList<Programme>>()
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(input, null)
        var channel: String? = null
        var start = 0L
        var end = 0L
        var title = ""
        var desc = ""
        var field: String? = null
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "programme" -> {
                        val ch = p.getAttributeValue(null, "channel")?.lowercase()
                        val s = parseXmltvTime(p.getAttributeValue(null, "start").orEmpty())
                        val e = parseXmltvTime(p.getAttributeValue(null, "stop").orEmpty())
                        channel = if (ch != null && ch in want && s != null && e != null && e > from && s < to) ch else null
                        start = s ?: 0L; end = e ?: 0L; title = ""; desc = ""
                    }
                    "title" -> if (channel != null && title.isEmpty()) field = "title"
                    "desc" -> if (channel != null && desc.isEmpty()) field = "desc"
                }
                XmlPullParser.TEXT -> when (field) {
                    "title" -> title += p.text
                    "desc" -> desc += p.text
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "title", "desc" -> field = null
                    "programme" -> {
                        channel?.let { out.getOrPut(it) { ArrayList() }.add(Programme(start, end, title.trim(), desc.trim())) }
                        channel = null
                    }
                }
            }
            event = p.next()
        }
        return out.mapValues { (_, v) -> v.sortedBy { it.start }.distinctBy { it.start } }
    }
}
