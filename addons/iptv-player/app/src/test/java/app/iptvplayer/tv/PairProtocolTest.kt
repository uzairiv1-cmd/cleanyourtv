package app.iptvplayer.tv

import app.iptvplayer.tv.pair.PairProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairProtocolTest {
    @Test fun parsesFormBodies() {
        val f = PairProtocol.parseForm("type=xtream&server=http%3A%2F%2Fh.tv%3A8080&username=bob&password=p%40ss+word&skip=on&&=x&bad=%zz")
        assertEquals("xtream", f["type"])
        assertEquals("http://h.tv:8080", f["server"])
        assertEquals("p@ss word", f["password"])
        assertEquals("on", f["skip"])
        assertEquals("%zz", f["bad"])
        assertFalse(f.containsKey(""))
    }

    @Test fun escapesWhatItEchoes() {
        val html = PairProtocol.formPage("", mapOf("name" to "<script>\"x\"</script>"), error = "Wrong & bad")
        assertFalse(html.contains("<script>\"x\""))
        assertTrue(html.contains("&lt;script&gt;&quot;x&quot;"))
        assertTrue(html.contains("Wrong &amp; bad"))
    }

    @Test fun pinIsHiddenWhenItCameWithTheQrCode() {
        assertTrue(PairProtocol.formPage("123456").contains("""type="hidden" name="pin" value="123456""""))
        assertTrue(PairProtocol.formPage("").contains("""id="pin" name="pin""""))
    }

    @Test fun keepsTheChosenType() {
        assertTrue(PairProtocol.formPage("1", mapOf("type" to "m3u")).contains("""id="tm" checked"""))
    }
}
