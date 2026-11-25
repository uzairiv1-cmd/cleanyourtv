package app.iptvplayer.tv

import app.iptvplayer.tv.source.Epg
import app.iptvplayer.tv.source.M3uParser
import app.iptvplayer.tv.source.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class EpgTest {
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test fun xtreamShortEpgWithBase64AndPlainText() {
        val json = """{"epg_listings":[
          {"title":"${b64("Late News")}","description":"${b64("The day's headlines")}","start_timestamp":"1700003600","stop_timestamp":"1700005400"},
          {"title":"${b64("Evening News")}","description":"","start_timestamp":"1700000000","stop_timestamp":"1700003600"},
          {"title":"Plain Title","description":"Plain text","start_timestamp":"1700005400","stop_timestamp":"1700007200"},
          {"title":"bad","start_timestamp":"x","stop_timestamp":"1"}]}"""
        val list = Epg.parseXtream(json)
        assertEquals(listOf("Evening News", "Late News", "Plain Title"), list.map { it.title })
        assertEquals("The day's headlines", list[1].description)
        assertEquals("Plain text", list[2].description)
        assertEquals(1_700_000_000_000L, list[0].start)
        assertTrue(Epg.parseXtream("garbage").isEmpty())
        assertTrue(Epg.parseXtream("""{"epg_listings":[]}""").isEmpty())
    }

    @Test fun nowNextAndMinutesLeft() {
        val a = Programme(0, 60 * 60_000L, "A")
        val b = Programme(60 * 60_000L, 105 * 60_000L, "B")
        val now = 45 * 60_000L + 30_000
        val (n, x) = Epg.nowNext(listOf(a, b), now)
        assertEquals("A", n?.title)
        assertEquals("B", x?.title)
        assertEquals(15, a.minutesLeft(now))
        assertEquals(45, b.minutes)
        assertEquals(0.7583f, a.progress(now), 0.001f)
        val (n2, x2) = Epg.nowNext(listOf(b), now)
        assertNull(n2)
        assertEquals("B", x2?.title)
    }

    @Test fun xmltvTimes() {
        assertEquals(1_704_106_800_000L, Epg.parseXmltvTime("20240101120000 +0100"))
        assertEquals(1_704_110_400_000L, Epg.parseXmltvTime("20240101120000"))
        assertNull(Epg.parseXmltvTime("2024"))
    }

    @Test fun xmltvKeepsWantedChannelsInTheWindow() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
 <channel id="one.ch"><display-name>One</display-name></channel>
 <programme start="20240101110000 +0000" stop="20240101120000 +0000" channel="One.ch"><title lang="en">Morning</title><desc>Coffee &amp; news</desc></programme>
 <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="one.ch"><title>Noon</title></programme>
 <programme start="20240101120000 +0000" stop="20240101130000 +0000" channel="other"><title>Skip</title></programme>
 <programme start="20240105120000 +0000" stop="20240105130000 +0000" channel="one.ch"><title>Too late</title></programme>
</tv>"""
        val from = Epg.parseXmltvTime("20240101000000 +0000")!!
        val to = Epg.parseXmltvTime("20240102000000 +0000")!!
        val g = Epg.parseXmltv(xml.byteInputStream(), setOf("one.ch"), from, to)
        assertEquals(setOf("one.ch"), g.keys)
        assertEquals(listOf("Morning", "Noon"), g.getValue("one.ch").map { it.title })
        assertEquals("Coffee & news", g.getValue("one.ch")[0].description)
    }

    @Test fun m3uHeaderGuideAndTvgId() {
        val p = M3uParser.parseFull(
            "#EXTM3U x-tvg-url=\"http://g/epg.xml.gz,http://g/b.xml\"\n#EXTINF:-1 tvg-id=\"one.ch\" group-title=\"N\",One\nhttp://h/1.ts\n#EXTINF:-1,Two\nhttp://h/2.ts\n"
                .reader().buffered()
        )
        assertEquals("http://g/epg.xml.gz", p.epgUrl)
        assertEquals("one.ch", p.entries[0].epgId)
        assertEquals("one.ch", M3uParser.toItem(p.entries[0]).epgId)
        assertEquals("Two", M3uParser.toItem(p.entries[1]).epgId)
    }
}
