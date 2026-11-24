package app.iptvplayer.tv.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import app.iptvplayer.tv.Playlist
import app.iptvplayer.tv.R
import app.iptvplayer.tv.SourceType
import app.iptvplayer.tv.data.Http
import app.iptvplayer.tv.data.Store
import app.iptvplayer.tv.source.XtreamApi
import app.iptvplayer.tv.source.XtreamParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Add or edit a playlist. An Xtream login is checked against the server before it is saved; an M3U
 * link from an Xtream panel (get.php?username=..&password=..) is recognised and saved as Xtream,
 * which loads faster and adds Movies and Series.
 */
class PlaylistEditActivity : Activity() {
    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_FIRST = "first"
    }

    private val scope = MainScope()
    private var type = SourceType.XTREAM
    private var editing: Playlist? = null
    private var checkedOnce = false

    private lateinit var tabXtream: TextView
    private lateinit var tabM3u: TextView
    private lateinit var name: EditText
    private lateinit var server: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var url: EditText
    private lateinit var status: TextView
    private lateinit var save: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)
        tabXtream = findViewById(R.id.type_xtream)
        tabM3u = findViewById(R.id.type_m3u)
        name = findViewById(R.id.name)
        server = findViewById(R.id.server)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        url = findViewById(R.id.url)
        status = findViewById(R.id.status)
        save = findViewById(R.id.save)

        if (intent.getBooleanExtra(EXTRA_FIRST, false)) findViewById<TextView>(R.id.edit_title).setText(R.string.welcome)
        editing = intent.getStringExtra(EXTRA_ID)?.let { id -> Store.playlists(this).firstOrNull { it.id == id } }
        editing?.let { p ->
            findViewById<TextView>(R.id.edit_title).setText(R.string.edit_playlist)
            name.setText(p.name)
            server.setText(p.server)
            username.setText(p.username)
            password.setText(p.password)
            url.setText(p.url)
            type = p.type
        }
        tabXtream.setOnClickListener { setType(SourceType.XTREAM) }
        tabM3u.setOnClickListener { setType(SourceType.M3U) }
        save.setOnClickListener { onSave() }
        // A changed field is checked again rather than saved with "Save anyway".
        val reset = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (checkedOnce) { checkedOnce = false; save.setText(R.string.save) }
            }
        }
        listOf(server, username, password, url).forEach { it.addTextChangedListener(reset) }
        setType(type)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun setType(t: SourceType) {
        type = t
        checkedOnce = false
        save.setText(R.string.save)
        status.visibility = View.GONE
        tabXtream.isActivated = t == SourceType.XTREAM
        tabM3u.isActivated = t == SourceType.M3U
        val x = if (t == SourceType.XTREAM) View.VISIBLE else View.GONE
        val m = if (t == SourceType.M3U) View.VISIBLE else View.GONE
        listOf(R.id.label_server, R.id.server, R.id.label_username, R.id.username, R.id.label_password, R.id.password)
            .forEach { findViewById<View>(it).visibility = x }
        listOf(R.id.label_url, R.id.url).forEach { findViewById<View>(it).visibility = m }
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun onSave() {
        val id = editing?.id ?: Store.newId()
        if (type == SourceType.M3U) {
            val link = url.text.toString().trim()
            if (!link.startsWith("http://", true) && !link.startsWith("https://", true)) {
                showStatus(getString(R.string.need_url)); return
            }
            val x = XtreamApi.fromLink(link)
            if (x != null) {
                // Same login as Xtream: switch over and check it.
                server.setText(x.first); username.setText(x.second); password.setText(x.third)
                setType(SourceType.XTREAM)
                Toast.makeText(this, R.string.recognised_xtream, Toast.LENGTH_LONG).show()
                onSave()
                return
            }
            val label = name.text.toString().trim().ifEmpty { hostOf(link) }
            finishWith(Playlist(id, label, SourceType.M3U, url = link))
            return
        }

        val srv = server.text.toString().trim()
        val user = username.text.toString().trim()
        val pass = password.text.toString().trim()
        val fromLink = XtreamApi.fromLink(srv)
        val p = if (fromLink != null) {
            Playlist(id, name.text.toString().trim().ifEmpty { hostOf(fromLink.first) }, SourceType.XTREAM,
                server = fromLink.first, username = fromLink.second, password = fromLink.third)
        } else {
            if (srv.isEmpty() || user.isEmpty() || pass.isEmpty()) { showStatus(getString(R.string.need_login)); return }
            val base = XtreamApi.normalizeServer(srv)
            Playlist(id, name.text.toString().trim().ifEmpty { hostOf(base) }, SourceType.XTREAM,
                server = base, username = user, password = pass)
        }
        if (checkedOnce) { finishWith(p); return } // "Save anyway" after a failed check

        save.isEnabled = false
        showStatus(getString(R.string.checking))
        scope.launch {
            val api = XtreamApi(p.server, p.username, p.password)
            val result = try {
                withContext(Dispatchers.IO) { XtreamParser.auth(Http.text(api.api(), 2 * 1024 * 1024)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null.also { showStatus(Http.friendly(e).message ?: getString(R.string.error_generic)) }
            }
            save.isEnabled = true
            if (result != null && result.ok) {
                val exp = result.expiresEpochSeconds?.let { DateFormat.getDateInstance().format(Date(it * 1000)) }
                Toast.makeText(
                    this@PlaylistEditActivity,
                    if (exp != null) getString(R.string.connected_until, exp) else getString(R.string.connected),
                    Toast.LENGTH_LONG,
                ).show()
                finishWith(p)
            } else {
                if (result != null) showStatus(result.message)
                checkedOnce = true
                save.setText(R.string.save_anyway)
            }
        }
    }

    private fun finishWith(p: Playlist) {
        Store.save(this, p)
        Store.setActive(this, p.id)
        finish()
    }

    private fun hostOf(link: String): String =
        link.substringAfter("://").substringBefore('/').substringBefore(':').ifEmpty { getString(R.string.app_name) }
}
