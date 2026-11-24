package app.iptvplayer.tv

enum class SourceType { XTREAM, M3U }

/** One saved playlist: an Xtream Codes account (server, username, password) or an M3U link. */
data class Playlist(
    val id: String,
    val name: String,
    val type: SourceType,
    val url: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
)

enum class Section { LIVE, MOVIES, SERIES }

data class Category(val id: String, val name: String) {
    companion object {
        const val FAVORITES = "__favorites"
        const val ALL = "__all"
    }
}

enum class Kind { LIVE, MOVIE, SERIES, EPISODE }

/**
 * Anything shown in a list. [url] is the stream address; a series has none (it opens its episodes).
 * [info] is a short second line: a rating, a year, an episode's season.
 */
data class Item(
    val id: String,
    val title: String,
    val logo: String?,
    val url: String?,
    val kind: Kind,
    val info: String = "",
) {
    val section: Section
        get() = when (kind) {
            Kind.LIVE -> Section.LIVE
            Kind.MOVIE -> Section.MOVIES
            Kind.SERIES, Kind.EPISODE -> Section.SERIES
        }
}
