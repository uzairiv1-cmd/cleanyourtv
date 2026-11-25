package app.iptvplayer.tv.data

import android.content.Context
import app.iptvplayer.tv.Playlist
import app.iptvplayer.tv.source.Source

/** The open playlist and its source, shared by the main screen and the player (one source, one cache). */
object Session {
    private var playlist: Playlist? = null
    private var source: Source? = null

    fun set(p: Playlist, s: Source) {
        playlist = p
        source = s
    }

    /** The current source, or one made from the saved active playlist when the app was restarted into the player. */
    fun source(c: Context): Source? {
        val active = Store.active(c) ?: return null
        if (playlist != active || source == null) set(active, Source.create(active))
        return source
    }
}
