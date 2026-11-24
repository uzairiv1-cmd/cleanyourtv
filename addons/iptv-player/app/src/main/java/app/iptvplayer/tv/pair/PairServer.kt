package app.iptvplayer.tv.pair

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.iptvplayer.tv.Playlist
import app.iptvplayer.tv.SourceType
import app.iptvplayer.tv.data.Http
import app.iptvplayer.tv.data.Store
import app.iptvplayer.tv.source.XtreamApi
import app.iptvplayer.tv.source.XtreamParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * A tiny web server on the home network, running only while the "Add from phone" screen is open.
 * A phone or computer opens its page, fills in a playlist, and the TV saves it. Every request needs
 * the six-digit code shown on the TV, so nobody else on the network can add playlists.
 */
class PairServer(private val context: Context, private val onAdded: (Playlist) -> Unit) {
    val pin: String = (SecureRandom().nextInt(900_000) + 100_000).toString()
    private var socket: ServerSocket? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false

    val port: Int get() = socket?.localPort ?: 0

    /** Listens on the first free port from 8090; returns false if none could be opened. */
    fun start(): Boolean {
        for (p in 8090..8099) {
            socket = try { ServerSocket(p, 8) } catch (e: IOException) { null }
            if (socket != null) break
        }
        if (socket == null) socket = try { ServerSocket(0, 8) } catch (e: IOException) { return false }
        val s = socket ?: return false
        thread(name = "pair-server", isDaemon = true) {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: IOException) { break }
                thread(name = "pair-request", isDaemon = true) { client.use { handle(it) } }
            }
        }
        return true
    }

    fun stop() {
        closed = true
        try { socket?.close() } catch (e: IOException) { /* already closed */ }
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 20_000
            val input = s.getInputStream().buffered()
            val requestLine = readLine(input) ?: return
            var length = 0
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                if (h.startsWith("Content-Length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrElse(0) { "" }
            val target = parts.getOrElse(1) { "/" }
            val path = target.substringBefore('?')
            val query = PairProtocol.parseForm(target.substringAfter('?', ""))
            if (method == "GET" && path == "/logo.png") {
                val png = context.resources.openRawResource(app.iptvplayer.tv.R.raw.logo_web).use { it.readBytes() }
                val out = s.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\nCache-Control: max-age=3600\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(png)
                out.flush()
                return
            }
            val (status, html) = when {
                closed -> "410 Gone" to PairProtocol.closedPage()
                method == "GET" && path == "/" -> {
                    val pinFromQr = query["pin"]?.takeIf { it == pin }.orEmpty()
                    "200 OK" to PairProtocol.formPage(pinFromQr)
                }
                method == "POST" && path == "/add" -> {
                    if (length !in 1..PairProtocol.MAX_BODY) "413 Payload Too Large" to PairProtocol.formPage("", error = "That was too much to send")
                    else "200 OK" to add(PairProtocol.parseForm(String(readBytes(input, length), Charsets.UTF_8)))
                }
                method == "GET" && path == "/favicon.ico" -> "404 Not Found" to ""
                else -> "404 Not Found" to PairProtocol.formPage("")
            }
            val body = html.toByteArray(Charsets.UTF_8)
            val out = s.getOutputStream()
            out.write(
                ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\nX-Frame-Options: DENY\r\nConnection: close\r\n\r\n").toByteArray()
            )
            out.write(body)
            out.flush()
        } catch (e: IOException) {
            // The phone went away; nothing to answer.
        }
    }

    /** Checks and saves what the phone sent; returns the page to show next. */
    private fun add(f: Map<String, String>): String {
        if (f["pin"]?.trim() != pin) return PairProtocol.formPage("", f - "pin", "That code doesn't match the one on the TV")
        val name = f["name"].orEmpty().trim()
        val skip = f["skip"] == "on"
        val id = Store.newId()

        if (f["type"] == "m3u") {
            val link = f["url"].orEmpty().trim()
            if (!link.startsWith("http://", true) && !link.startsWith("https://", true)) {
                return PairProtocol.formPage(pin, f, "Enter a link that starts with http:// or https://")
            }
            val x = XtreamApi.fromLink(link)
            if (x == null) return save(Playlist(id, name.ifEmpty { hostOf(link) }, SourceType.M3U, url = link))
            return addXtream(id, name, x.first, x.second, x.third, skip, f)
        }
        var server = f["server"].orEmpty().trim()
        var user = f["username"].orEmpty().trim()
        var pass = f["password"].orEmpty().trim()
        XtreamApi.fromLink(server)?.let { server = it.first; user = it.second; pass = it.third }
        if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            return PairProtocol.formPage(pin, f, "Enter the server, username and password")
        }
        return addXtream(id, name, server, user, pass, skip, f)
    }

    private fun addXtream(id: String, name: String, server: String, user: String, pass: String, skip: Boolean, f: Map<String, String>): String {
        val api = XtreamApi(server, user, pass)
        if (!skip) {
            val problem = try {
                val info = XtreamParser.auth(Http.text(api.api(), 2 * 1024 * 1024))
                if (info.ok) null else info.message
            } catch (e: IOException) {
                Http.friendly(e).message
            }
            if (problem != null) return PairProtocol.formPage(pin, f, "$problem. Check it, or tick “Save without checking”.")
        }
        return save(Playlist(id, name.ifEmpty { hostOf(api.base) }, SourceType.XTREAM, server = api.base, username = user, password = pass))
    }

    private fun save(p: Playlist): String {
        Store.save(context, p)
        Store.setActive(context, p.id)
        main.post { onAdded(p) }
        return PairProtocol.donePage(p.name)
    }

    private fun hostOf(link: String) = link.substringAfter("://").substringBefore('/').substringBefore(':').ifEmpty { "IPTV" }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            if (buf.size() > 8192) throw IOException("Header line too long")
            buf.write(b)
        }
    }

    private fun readBytes(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(out, read, n - read)
            if (r < 0) break
            read += r
        }
        return out.copyOf(read)
    }

    companion object {
        /** The TV's address on the home network (Wi-Fi or Ethernet), or null when it has none. */
        fun lanAddress(): String? = try {
            val v4 = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            // A home network address (192.168.x.x, 10.x.x.x, 172.16-31.x.x) first; any other one otherwise.
            (v4.firstOrNull { it.isSiteLocalAddress } ?: v4.firstOrNull())?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}
