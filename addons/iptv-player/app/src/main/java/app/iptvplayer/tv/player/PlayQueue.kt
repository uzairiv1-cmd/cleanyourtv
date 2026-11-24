package app.iptvplayer.tv.player

import app.iptvplayer.tv.Item

/** The list the player zaps through. Held here rather than in the intent: a live list can have thousands of entries. */
object PlayQueue {
    var items: List<Item> = emptyList()
        private set
    var index = 0
        private set

    /** Where the list came from ("Sports", "Favourites"), shown in the player. */
    var title: String = ""
        private set

    /** The playlist being watched, for the watch history. */
    var playlistId: String = ""
        private set

    fun set(list: List<Item>, start: Int, title: String = "", playlistId: String = "") {
        this.title = title
        this.playlistId = playlistId
        items = list
        index = start.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }

    val current: Item? get() = items.getOrNull(index)

    fun jump(i: Int): Item? {
        if (i !in items.indices) return null
        index = i
        return current
    }

    fun move(delta: Int): Item? {
        if (items.isEmpty()) return null
        index = Math.floorMod(index + delta, items.size)
        return current
    }
}
