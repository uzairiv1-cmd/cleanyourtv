package app.iptvplayer.tv

import app.iptvplayer.tv.source.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {
    private fun parse(text: String) = M3uParser.parse(text.reader().buffered())

    @Test fun readsAttributesNameAndGroup() {
        val list = parse(
            """
            #EXTM3U x-tvg-url="http://example.com/epg.xml"
            #EXTINF:-1 tvg-id="one.uk" tvg-name="One HD" tvg-logo="http://img/1.png" group-title="News, UK",BBC One
            http://example.com/live/u/p/1.ts
            #EXTINF:-1,No Attributes
            #EXTGRP:Sport
            https://example.com/2.m3u8
            """.trimIndent()
        )
        assertEquals(2, list.size)
        assertEquals("BBC One", list[0].title)
        assertEquals("http://img/1.png", list[0].logo)
        assertEquals("News, UK", list[0].group)
        assertEquals(Kind.LIVE, list[0].kind)
        assertEquals("No Attributes", list[1].title)
        assertEquals("Sport", list[1].group)
        assertNull(list[1].logo)
    }

    @Test fun fallsBackToTvgNameAndFileName() {
        val list = parse("#EXTINF:-1 tvg-name=\"From Attr\",\nhttp://h/a.ts\nhttp://h/b.ts?token=1\n")
        assertEquals("From Attr", list[0].title)
        assertEquals("b.ts", list[1].title)
        assertEquals("Uncategorised", list[1].group)
    }

    @Test fun classifiesMoviesAndEpisodes() {
        assertEquals(Kind.MOVIE, M3uParser.kindOf("http://h:80/movie/u/p/12.mkv"))
        assertEquals(Kind.EPISODE, M3uParser.kindOf("http://h:80/series/u/p/99.mp4"))
        assertEquals(Kind.MOVIE, M3uParser.kindOf("http://cdn/film.MP4?x=1"))
        assertEquals(Kind.LIVE, M3uParser.kindOf("http://h:80/u/p/5"))
        assertEquals(Kind.LIVE, M3uParser.kindOf("http://h/live/index.m3u8"))
    }

    @Test fun ignoresBomAndNonHttpEntries() {
        val list = parse("﻿#EXTM3U\n#EXTINF:-1,Udp\nudp://@239.0.0.1:1234\n#EXTINF:-1,Ok\nhttp://h/ok.ts\n")
        assertEquals(1, list.size)
        assertEquals("Ok", list[0].title)
    }
}
