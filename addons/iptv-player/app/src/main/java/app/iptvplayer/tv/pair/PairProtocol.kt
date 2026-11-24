package app.iptvplayer.tv.pair

import java.net.URLDecoder

/**
 * The web page the TV serves to a phone or computer on the same network, and the parsing of what it
 * sends back. No Android here, so it is unit-tested on the JVM.
 */
object PairProtocol {
    const val MAX_BODY = 64 * 1024

    /** `a=1&b=x%20y` (a query string or a form body) to a map; a broken escape keeps the raw text. */
    fun parseForm(body: String): Map<String, String> = body.split('&').mapNotNull { part ->
        if (part.isEmpty()) return@mapNotNull null
        val k = decode(part.substringBefore('='))
        if (k.isEmpty()) null else k to decode(part.substringAfter('=', ""))
    }.toMap()

    private fun decode(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: IllegalArgumentException) { s }

    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;")
            '"' -> append("&quot;"); '\'' -> append("&#39;"); else -> append(c)
        }
    }

    private const val LOGO = """<svg class="logo" viewBox="0 50 740 530" aria-label="AV"><path fill="#FE5D09" fill-rule="evenodd" d="M256 66 L440 410 L140 410 L96 500 L8 500 Z M250 214 L184 350 L316 350 Z"/><path fill="#8A8A8A" d="M400 300 L462 300 L491 462 L626 198 L716 198 L528 566 L448 566 Z"/></svg>"""

    private const val STYLE = """
*{box-sizing:border-box;margin:0}
body{min-height:100vh;font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;color:#EEF0F4;
 background:radial-gradient(600px 500px at 100% 0,rgba(254,93,9,.18),transparent 70%),radial-gradient(500px 400px at 0 100%,rgba(200,60,0,.13),transparent 70%),#050505;
 display:flex;justify-content:center;padding:28px 16px}
main{width:100%;max-width:460px}
.logo{width:84px;height:auto;display:block}
h1{font-size:28px;font-weight:800;margin:18px 0 4px;letter-spacing:-.3px}
h1 span{color:#FF7A2E}
p.sub{color:#9AA0AA;margin-bottom:22px}
.card{background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.12);border-radius:18px;padding:20px}
.tabs{display:flex;gap:8px;margin-bottom:18px}
.tabs label{flex:1;text-align:center;padding:10px;border-radius:999px;border:1px solid rgba(255,255,255,.14);cursor:pointer;color:#ddd;font-weight:600}
#tx:checked~.tabs label[for=tx],#tm:checked~.tabs label[for=tm]{background:linear-gradient(135deg,#FF7A2E,#E04E00);color:#111;border-color:transparent}
label.f{display:block;font-size:13px;color:#9AA0AA;margin:14px 0 6px}
input.t{width:100%;padding:13px 14px;border-radius:12px;border:1px solid rgba(255,255,255,.14);background:#0d0d0d;color:#fff;font-size:16px}
input.t:focus{outline:2px solid #FE5D09;border-color:transparent}
.x,.m{display:none}
#tx:checked~.fields .x,#tm:checked~.fields .m{display:block}
.check{display:flex;gap:10px;align-items:center;margin-top:16px;color:#bbb;font-size:14px}
button{width:100%;margin-top:22px;padding:15px;border:0;border-radius:999px;font-size:17px;font-weight:800;color:#111;
 background:linear-gradient(135deg,#FF7A2E,#E04E00);cursor:pointer}
.err{background:rgba(255,77,77,.12);border:1px solid rgba(255,77,77,.4);color:#ffb3b3;border-radius:12px;padding:12px 14px;margin-bottom:16px}
.ok{font-size:56px;color:#FF7A2E;line-height:1}
.note{color:#777;font-size:13px;margin-top:18px}
"""

    private fun page(title: String, body: String) = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title><style>$STYLE</style></head>
<body><main>$LOGO$body</main></body></html>"""

    /** The form. [values] refills it after an error; [pin] comes from the QR code's address. */
    fun formPage(pin: String, values: Map<String, String> = emptyMap(), error: String? = null): String {
        fun v(k: String) = esc(values[k].orEmpty())
        val m3u = values["type"] == "m3u"
        val pinField = if (pin.isNotEmpty()) {
            """<input type="hidden" name="pin" value="${esc(pin)}">"""
        } else {
            """<label class="f" for="pin">Code shown on the TV</label><input class="t" id="pin" name="pin" inputmode="numeric" autocomplete="one-time-code" maxlength="6" required value="${v("pin")}">"""
        }
        return page("Add a playlist", """
<h1>Add a playlist to your <span>TV</span></h1>
<p class="sub">It appears on the TV as soon as you press Add.</p>
<form class="card" method="post" action="/add" autocomplete="off">
${if (error != null) """<div class="err">${esc(error)}</div>""" else ""}
<input type="radio" name="type" value="xtream" id="tx" ${if (!m3u) "checked" else ""} hidden>
<input type="radio" name="type" value="m3u" id="tm" ${if (m3u) "checked" else ""} hidden>
<div class="tabs"><label for="tx">Xtream Codes</label><label for="tm">M3U link</label></div>
<div class="fields">
<label class="f" for="name">Name (optional)</label><input class="t" id="name" name="name" placeholder="Living Room" value="${v("name")}">
<div class="x">
<label class="f" for="server">Server</label><input class="t" id="server" name="server" inputmode="url" placeholder="http://example.com:8080" value="${v("server")}">
<label class="f" for="username">Username</label><input class="t" id="username" name="username" autocapitalize="none" value="${v("username")}">
<label class="f" for="password">Password</label><input class="t" id="password" name="password" type="password" value="${v("password")}">
</div>
<div class="m">
<label class="f" for="url">M3U / M3U8 link</label><input class="t" id="url" name="url" inputmode="url" placeholder="https://…/playlist.m3u" value="${v("url")}">
</div>
$pinField
<label class="check"><input type="checkbox" name="skip" ${if (values["skip"] == "on") "checked" else ""}> Save without checking the login</label>
</div>
<button type="submit">Add to TV</button>
<p class="note">Your login goes straight to your TV on this Wi-Fi and is kept only there.</p>
</form>""")
    }

    fun donePage(name: String) = page("Added", """
<div class="card" style="margin-top:22px;text-align:center">
<div class="ok">✓</div>
<h1 style="margin-top:10px">Added to your <span>TV</span></h1>
<p class="sub" style="margin:6px 0 0">“${esc(name)}” is open on the TV now. You can close this page.</p>
</div>""")

    fun closedPage() = page("Closed", """
<div class="card" style="margin-top:22px"><h1>This page has closed</h1>
<p class="sub" style="margin:6px 0 0">On the TV, open <b>Settings → Add from phone</b> again and scan the new code.</p></div>""")
}
