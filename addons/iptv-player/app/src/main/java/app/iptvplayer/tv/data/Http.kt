package app.iptvplayer.tv.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Plain HttpURLConnection for a few GETs. Follows redirects between http and https, which Java does not by itself. */
object Http {
    const val USER_AGENT = "AVPlayer/1.2 (Linux; Android TV)"

    class HttpError(message: String) : IOException(message)

    fun open(url: String): InputStream {
        var current = url
        repeat(6) {
            val c = try { URL(current).openConnection() as HttpURLConnection } catch (e: Exception) { throw HttpError("That address is not valid") }
            c.connectTimeout = 15_000
            c.readTimeout = 45_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            val code = try { c.responseCode } catch (e: IOException) { c.disconnect(); throw friendly(e) }
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrEmpty()) throw HttpError("The server redirected without saying where")
                current = URL(URL(current), loc).toString()
                return@repeat
            }
            if (code !in 200..299) {
                c.disconnect()
                throw HttpError(
                    when (code) {
                        401, 403 -> "The server refused access (HTTP $code). Check the login, or whether the account has expired"
                        404 -> "Nothing at that address (HTTP 404)"
                        else -> "The server answered HTTP $code"
                    }
                )
            }
            return c.inputStream
        }
        throw HttpError("Too many redirects")
    }

    fun bytes(url: String, maxBytes: Int): ByteArray = open(url).use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = try { input.read(buf) } catch (e: IOException) { throw friendly(e) }
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > maxBytes) throw HttpError("The answer is larger than ${maxBytes / (1024 * 1024)} MB")
        }
        out.toByteArray()
    }

    fun text(url: String, maxBytes: Int = 48 * 1024 * 1024): String = String(bytes(url, maxBytes), Charsets.UTF_8)

    fun friendly(e: Throwable): IOException = when (e) {
        is HttpError -> e
        is UnknownHostException -> HttpError("Can't find that server. Check the address and the TV's network")
        is ConnectException -> HttpError("The server did not accept the connection. Check the address and port")
        is SocketTimeoutException -> HttpError("The server took too long to answer")
        is SSLException -> HttpError("Secure connection failed. Try http:// instead of https://")
        is IOException -> HttpError(e.message ?: "Network error")
        else -> HttpError(e.toString())
    }
}
