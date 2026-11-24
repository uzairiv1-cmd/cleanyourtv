package app.iptvplayer.tv.data

import android.content.Context
import android.content.SharedPreferences
import app.iptvplayer.tv.Item
import app.iptvplayer.tv.Kind
import app.iptvplayer.tv.Playlist
import app.iptvplayer.tv.SourceType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Playlists, favourites and resume positions, in the app's private preferences. Backups are off in
 * the manifest, so logins never leave the TV.
 */
object Store {
    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    fun newId(): String = UUID.randomUUID().toString()

    fun playlists(c: Context): List<Playlist> {
        val arr = try { JSONArray(prefs(c).getString("playlists", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::playlistOf) }
    }

    fun save(c: Context, p: Playlist) {
        val list = playlists(c).toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        write(c, list)
    }

    fun delete(c: Context, id: String) {
        write(c, playlists(c).filter { it.id != id })
        prefs(c).edit().remove("fav_$id").remove("recent_$id").apply()
        if (prefs(c).getString("active", null) == id) prefs(c).edit().remove("active").apply()
    }

    fun setActive(c: Context, id: String) = prefs(c).edit().putString("active", id).apply()

    fun active(c: Context): Playlist? {
        val all = playlists(c)
        val id = prefs(c).getString("active", null)
        return all.firstOrNull { it.id == id } ?: all.firstOrNull()
    }

    private fun write(c: Context, list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().put("id", p.id).put("name", p.name).put("type", p.type.name).put("url", p.url)
                    .put("server", p.server).put("username", p.username).put("password", p.password)
            )
        }
        prefs(c).edit().putString("playlists", arr.toString()).apply()
    }

    private fun playlistOf(o: JSONObject): Playlist? {
        val type = try { SourceType.valueOf(o.optString("type")) } catch (e: IllegalArgumentException) { return null }
        return Playlist(
            o.optString("id"), o.optString("name"), type, o.optString("url"),
            o.optString("server"), o.optString("username"), o.optString("password"),
        )
    }

    // Favourites and the watch history: whole items, so they show without asking the server.

    fun favorites(c: Context, playlistId: String): List<Item> = items(c, "fav_$playlistId")

    fun recents(c: Context, playlistId: String): List<Item> = items(c, "recent_$playlistId")

    /** Puts [item] first in the watch history (20 kept). */
    fun addRecent(c: Context, playlistId: String, item: Item) {
        val list = recents(c, playlistId).filterNot { it.kind == item.kind && it.id == item.id }.toMutableList()
        list.add(0, item)
        writeItems(c, "recent_$playlistId", list.take(20))
    }

    fun clearHistory(c: Context) {
        val e = prefs(c).edit()
        prefs(c).all.keys.filter { it.startsWith("recent_") || it.startsWith("pos_") || it.startsWith("dur_") }.forEach { e.remove(it) }
        e.apply()
    }

    private fun items(c: Context, key: String): List<Item> {
        val arr = try { JSONArray(prefs(c).getString(key, "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = try { Kind.valueOf(o.optString("kind")) } catch (e: IllegalArgumentException) { return@mapNotNull null }
            Item(
                o.optString("id"), o.optString("title"), o.optString("logo").ifEmpty { null },
                o.optString("url").ifEmpty { null }, kind, o.optString("info"),
            )
        }
    }

    fun isFavorite(c: Context, playlistId: String, item: Item) =
        favorites(c, playlistId).any { it.kind == item.kind && it.id == item.id }

    /** Adds or removes; returns true when the item is now a favourite. */
    fun toggleFavorite(c: Context, playlistId: String, item: Item): Boolean {
        val list = favorites(c, playlistId).toMutableList()
        val existing = list.indexOfFirst { it.kind == item.kind && it.id == item.id }
        val added = existing < 0
        if (added) list.add(item) else list.removeAt(existing)
        writeItems(c, "fav_$playlistId", list)
        return added
    }

    private fun writeItems(c: Context, key: String, list: List<Item>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("title", it.title).put("logo", it.logo ?: "")
                    .put("url", it.url ?: "").put("kind", it.kind.name).put("info", it.info)
            )
        }
        prefs(c).edit().putString(key, arr.toString()).apply()
    }

    // Settings.

    fun previewEnabled(c: Context) = prefs(c).getBoolean("set_preview", true)
    fun setPreviewEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("set_preview", on).apply()
    fun resumeEnabled(c: Context) = prefs(c).getBoolean("set_resume", true)
    fun setResumeEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("set_resume", on).apply()

    // Where a movie or episode was left, so it starts there next time.

    fun position(c: Context, url: String): Long =
        if (resumeEnabled(c)) prefs(c).getLong("pos_" + url.hashCode(), 0L) else 0L

    /** How far through a movie or episode (0..1), or null when not started. */
    fun progress(c: Context, url: String): Float? {
        val pos = prefs(c).getLong("pos_" + url.hashCode(), 0L)
        val dur = prefs(c).getLong("dur_" + url.hashCode(), 0L)
        return if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else null
    }

    fun savePosition(c: Context, url: String, positionMs: Long, durationMs: Long) {
        val key = "pos_" + url.hashCode()
        val e = prefs(c).edit()
        val durKey = "dur_" + url.hashCode()
        if (positionMs > 30_000 && durationMs > 0 && durationMs - positionMs > 60_000) {
            e.putLong(key, positionMs).putLong(durKey, durationMs)
        } else {
            e.remove(key).remove(durKey)
        }
        e.apply()
    }
}
