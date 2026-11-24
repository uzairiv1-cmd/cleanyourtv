package app.iptvplayer.tv.source

import app.iptvplayer.tv.Category
import app.iptvplayer.tv.Item
import app.iptvplayer.tv.Kind
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/** Builds the addresses of the Xtream Codes player API and its stream paths. */
class XtreamApi(server: String, val username: String, val password: String) {
    val base: String = normalizeServer(server)

    fun api(action: String? = null, vararg params: Pair<String, String>): String {
        val sb = StringBuilder("$base/player_api.php?username=${q(username)}&password=${q(password)}")
        if (action != null) sb.append("&action=").append(action)
        params.forEach { (k, v) -> sb.append('&').append(k).append('=').append(q(v)) }
        return sb.toString()
    }

    fun liveUrl(id: String, ext: String) = "$base/live/${seg(username)}/${seg(password)}/$id.$ext"
    fun movieUrl(id: String, ext: String) = "$base/movie/${seg(username)}/${seg(password)}/$id.${ext.ifBlank { "mp4" }}"
    fun episodeUrl(id: String, ext: String) = "$base/series/${seg(username)}/${seg(password)}/$id.${ext.ifBlank { "mp4" }}"

    companion object {
        /** Accepts `host:port`, `http://host:port/`, or a pasted player_api.php / get.php address. */
        fun normalizeServer(server: String): String {
            var s = server.trim()
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            for (marker in listOf("/player_api.php", "/get.php", "/xmltv.php")) {
                val i = s.indexOf(marker, ignoreCase = true)
                if (i > 0) s = s.substring(0, i)
            }
            s = s.substringBefore('?')
            return s.trimEnd('/')
        }

        /**
         * An M3U address from an Xtream panel (`http://host/get.php?username=u&password=p&type=m3u_plus`)
         * carries the login itself. Returns (server, username, password), or null for any other link.
         */
        fun fromLink(link: String): Triple<String, String, String>? {
            val l = link.trim()
            if (!l.contains("/get.php", true) && !l.contains("/player_api.php", true)) return null
            val params = l.substringAfter('?', "").split('&').mapNotNull {
                val k = it.substringBefore('=', "")
                if (k.isEmpty()) null else k.lowercase() to decode(it.substringAfter('='))
            }.toMap()
            val user = params["username"].orEmpty()
            val pass = params["password"].orEmpty()
            if (user.isBlank() || pass.isBlank()) return null
            return Triple(normalizeServer(l), user, pass)
        }

        private fun q(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun seg(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun decode(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: IllegalArgumentException) { s }
    }
}

data class AuthInfo(
    val ok: Boolean,
    val message: String,
    val status: String = "",
    val expiresEpochSeconds: Long? = null,
    val maxConnections: String = "",
    val formats: List<String> = emptyList(),
)

/** Turns player_api.php answers into models. Panels differ: numbers arrive as strings or numbers, lists as arrays or objects. */
object XtreamParser {

    fun auth(json: String): AuthInfo {
        val root = try { JSONObject(json.trim()) } catch (e: JSONException) {
            return AuthInfo(false, "The server did not answer like an Xtream Codes panel. Check the address and port")
        }
        val ui = root.optJSONObject("user_info")
            ?: return AuthInfo(false, "The server did not answer like an Xtream Codes panel. Check the address and port")
        val formats = ui.optJSONArray("allowed_output_formats")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val status = s(ui, "status")
        val ok = s(ui, "auth") == "1" && (status.isEmpty() || status.equals("Active", true))
        val msg = when {
            s(ui, "auth") != "1" -> "Wrong username or password"
            !ok -> "The account is $status"
            else -> "Connected"
        }
        return AuthInfo(ok, msg, status, s(ui, "exp_date").toLongOrNull(), s(ui, "max_connections"), formats)
    }

    fun categories(json: String): List<Category> =
        objects(json).map { Category(s(it, "category_id"), s(it, "category_name").ifBlank { "Unnamed" }) }
            .filter { it.id.isNotEmpty() }

    fun live(json: String, api: XtreamApi, ext: String): List<Item> = objects(json).mapNotNull { o ->
        val id = s(o, "stream_id")
        if (id.isEmpty()) return@mapNotNull null
        Item(id, s(o, "name").ifBlank { "Channel $id" }, s(o, "stream_icon").ifBlank { null }, api.liveUrl(id, ext), Kind.LIVE)
    }

    fun movies(json: String, api: XtreamApi): List<Item> = objects(json).mapNotNull { o ->
        val id = s(o, "stream_id")
        if (id.isEmpty()) return@mapNotNull null
        Item(
            id, s(o, "name").ifBlank { "Movie $id" }, s(o, "stream_icon").ifBlank { null },
            api.movieUrl(id, s(o, "container_extension")), Kind.MOVIE, rating(s(o, "rating")),
        )
    }

    fun series(json: String): List<Item> = objects(json).mapNotNull { o ->
        val id = s(o, "series_id")
        if (id.isEmpty()) return@mapNotNull null
        val year = s(o, "releaseDate").ifBlank { s(o, "release_date") }.take(4)
        Item(
            id, s(o, "name").ifBlank { "Series $id" }, s(o, "cover").ifBlank { null }, null, Kind.SERIES,
            listOf(year, rating(s(o, "rating"))).filter { it.isNotBlank() }.joinToString("  ·  "),
        )
    }

    /** get_series_info: `episodes` is an object keyed by season, or an array of season arrays. */
    fun episodes(json: String, api: XtreamApi, fallbackLogo: String?): List<Item> {
        val root = try { JSONObject(json.trim()) } catch (e: JSONException) { return emptyList() }
        val cover = root.optJSONObject("info")?.let { s(it, "cover") }?.ifBlank { null } ?: fallbackLogo
        val seasons = ArrayList<Pair<Int, JSONArray>>()
        when (val eps = root.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k -> eps.optJSONArray(k)?.let { seasons.add((k.toIntOrNull() ?: 0) to it) } }
            is JSONArray -> for (i in 0 until eps.length()) {
                when (val v = eps.opt(i)) {
                    is JSONArray -> seasons.add(i + 1 to v)
                    is JSONObject -> seasons.add(0 to JSONArray().put(v))
                }
            }
        }
        seasons.sortBy { it.first }
        val out = ArrayList<Item>()
        for ((seasonKey, arr) in seasons) for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val id = s(e, "id")
            if (id.isEmpty()) continue
            val season = s(e, "season").toIntOrNull() ?: seasonKey
            val num = s(e, "episode_num").toIntOrNull()
            val label = buildString {
                if (season > 0) append("S").append(season.toString().padStart(2, '0'))
                if (num != null) append(if (isEmpty()) "E" else " E").append(num.toString().padStart(2, '0'))
            }
            val title = s(e, "title").ifBlank { "Episode ${num ?: (i + 1)}" }
            val image = e.optJSONObject("info")?.let { s(it, "movie_image") }?.ifBlank { null } ?: cover
            val duration = e.optJSONObject("info")?.let { s(it, "duration") }.orEmpty()
            out.add(
                Item(
                    id, if (label.isEmpty()) title else "$label  $title", image,
                    api.episodeUrl(id, s(e, "container_extension")), Kind.EPISODE, duration,
                )
            )
        }
        return out
    }

    /** The array of objects in an answer; some panels send `{"0":{..},"1":{..}}` or `{}` for an empty list. */
    internal fun objects(json: String): List<JSONObject> {
        val t = json.trim()
        return try {
            when {
                t.startsWith("[") -> JSONArray(t).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                t.startsWith("{") -> JSONObject(t).let { o -> o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.toList() }
                else -> emptyList()
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    /** A field as text: "" for missing or JSON null (org.json's optString would give "null"). */
    internal fun s(o: JSONObject, key: String): String =
        if (!o.has(key) || o.isNull(key)) "" else o.opt(key).toString().trim()

    private fun rating(r: String): String {
        val v = r.toDoubleOrNull() ?: return ""
        if (v <= 0.0) return ""
        return "★ " + if (v == Math.floor(v)) v.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)
    }
}
