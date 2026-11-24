package app.iptvplayer.tv

import app.iptvplayer.tv.source.XtreamApi
import app.iptvplayer.tv.source.XtreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamTest {
    private val api = XtreamApi("example.com:8080/", "user", "p@ss word")

    @Test fun normalizesServer() {
        assertEquals("http://example.com:8080", api.base)
        assertEquals("https://h.tv", XtreamApi.normalizeServer(" https://h.tv/player_api.php?username=a "))
        assertEquals("http://h.tv:80", XtreamApi.normalizeServer("h.tv:80/get.php?username=a&password=b"))
    }

    @Test fun buildsUrls() {
        assertEquals(
            "http://example.com:8080/player_api.php?username=user&password=p%40ss+word&action=get_live_streams&category_id=7",
            api.api("get_live_streams", "category_id" to "7"),
        )
        assertEquals("http://example.com:8080/live/user/p%40ss%20word/42.m3u8", api.liveUrl("42", "m3u8"))
        assertEquals("http://example.com:8080/movie/user/p%40ss%20word/9.mp4", api.movieUrl("9", ""))
        assertEquals("http://example.com:8080/series/user/p%40ss%20word/3.mkv", api.episodeUrl("3", "mkv"))
    }

    @Test fun recognisesGetPhpLinks() {
        val t = XtreamApi.fromLink("http://h.tv:8080/get.php?username=bob&password=s%26cret&type=m3u_plus&output=ts")!!
        assertEquals(Triple("http://h.tv:8080", "bob", "s&cret"), t)
        assertNull(XtreamApi.fromLink("http://h.tv/playlist.m3u"))
        assertNull(XtreamApi.fromLink("http://h.tv/get.php?username=bob"))
    }

    @Test fun parsesAuth() {
        val ok = XtreamParser.auth(
            """{"user_info":{"auth":1,"status":"Active","exp_date":"1767225600","max_connections":"1",
               "allowed_output_formats":["m3u8","ts"]},"server_info":{}}"""
        )
        assertTrue(ok.ok)
        assertEquals(1767225600L, ok.expiresEpochSeconds)
        assertEquals(listOf("m3u8", "ts"), ok.formats)
        val wrong = XtreamParser.auth("""{"user_info":{"auth":0}}""")
        assertFalse(wrong.ok)
        assertEquals("Wrong username or password", wrong.message)
        val expired = XtreamParser.auth("""{"user_info":{"auth":"1","status":"Expired","exp_date":null}}""")
        assertFalse(expired.ok)
        assertNull(expired.expiresEpochSeconds)
        assertFalse(XtreamParser.auth("<html>nope</html>").ok)
    }

    @Test fun parsesCategoriesAsArrayOrObject() {
        val a = XtreamParser.categories("""[{"category_id":"1","category_name":"News"},{"category_id":2,"category_name":null}]""")
        assertEquals(listOf(Category("1", "News"), Category("2", "Unnamed")), a)
        val o = XtreamParser.categories("""{"0":{"category_id":"5","category_name":"Kids"}}""")
        assertEquals(listOf(Category("5", "Kids")), o)
        assertEquals(emptyList<Category>(), XtreamParser.categories("{}"))
        assertEquals(emptyList<Category>(), XtreamParser.categories(""))
    }

    @Test fun parsesStreams() {
        val live = XtreamParser.live("""[{"stream_id":42,"name":"News 24","stream_icon":""},{"name":"no id"}]""", api, "ts")
        assertEquals(1, live.size)
        assertEquals("News 24", live[0].title)
        assertNull(live[0].logo)
        assertEquals("http://example.com:8080/live/user/p%40ss%20word/42.ts", live[0].url)

        val movies = XtreamParser.movies("""[{"stream_id":"9","name":"Film","stream_icon":"http://i/9.jpg","rating":"7.25","container_extension":"mkv"}]""", api)
        assertEquals("★ 7.3", movies[0].info)
        assertEquals(Kind.MOVIE, movies[0].kind)
        assertTrue(movies[0].url!!.endsWith("/9.mkv"))

        val series = XtreamParser.series("""[{"series_id":3,"name":"Show","cover":"http://i/c.jpg","releaseDate":"2019-05-01","rating":"8"}]""")
        assertEquals("2019  ·  ★ 8", series[0].info)
        assertNull(series[0].url)
    }

    @Test fun parsesEpisodesObjectAndArrayForms() {
        val obj = XtreamParser.episodes(
            """{"info":{"cover":"http://i/c.jpg"},"episodes":{
                "2":[{"id":"21","episode_num":1,"title":"Back","container_extension":"mp4","season":2}],
                "1":[{"id":"11","episode_num":"1","title":"Pilot","container_extension":"mkv","season":1,"info":{"movie_image":"http://i/e.jpg","duration":"00:42:00"}},
                     {"id":"12","episode_num":2,"title":"","container_extension":"mkv"}]}}""",
            api, null,
        )
        assertEquals(listOf("11", "12", "21"), obj.map { it.id })
        assertEquals("S01 E01  Pilot", obj[0].title)
        assertEquals("http://i/e.jpg", obj[0].logo)
        assertEquals("00:42:00", obj[0].info)
        assertEquals("S01 E02  Episode 2", obj[1].title)
        assertEquals("http://i/c.jpg", obj[1].logo)
        assertTrue(obj[2].url!!.endsWith("/series/user/p%40ss%20word/21.mp4"))

        val arr = XtreamParser.episodes("""{"episodes":[[{"id":"7","episode_num":3,"title":"T"}]]}""", api, "http://fallback")
        assertEquals("S01 E03  T", arr[0].title)
        assertEquals("http://fallback", arr[0].logo)

        assertEquals(emptyList<Item>(), XtreamParser.episodes("""{"episodes":[]}""", api, null))
        assertEquals(emptyList<Item>(), XtreamParser.episodes("garbage", api, null))
    }
}
