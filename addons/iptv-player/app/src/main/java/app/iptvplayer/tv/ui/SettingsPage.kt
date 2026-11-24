package app.iptvplayer.tv.ui

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.iptvplayer.tv.R
import app.iptvplayer.tv.data.Store

/** Settings: playlists, live preview, resume, and clearing the watch history. */
class SettingsPage(host: MainActivity) : Page(host, R.layout.page_settings) {
    private val rows: LinearLayout = find(R.id.rows)
    private val first: View

    init {
        first = row(R.string.set_pair, R.string.set_pair_sub, { "" }) {
            host.startActivity(Intent(host, app.iptvplayer.tv.pair.PairActivity::class.java))
        }
        row(R.string.set_playlists, R.string.set_playlists_sub, { "" }) {
            host.startActivity(Intent(host, PlaylistsActivity::class.java))
        }
        row(R.string.set_add, R.string.set_add_sub, { "" }) {
            host.startActivity(Intent(host, PlaylistEditActivity::class.java))
        }
        row(R.string.set_preview, R.string.set_preview_sub, { onOff(Store.previewEnabled(host)) }) {
            Store.setPreviewEnabled(host, !Store.previewEnabled(host))
        }
        row(R.string.set_resume, R.string.set_resume_sub, { onOff(Store.resumeEnabled(host)) }) {
            Store.setResumeEnabled(host, !Store.resumeEnabled(host))
        }
        row(R.string.set_clear, R.string.set_clear_sub, { "" }) {
            Store.clearHistory(host)
            Toast.makeText(host, R.string.cleared, Toast.LENGTH_SHORT).show()
        }
        val version = try { host.packageManager.getPackageInfo(host.packageName, 0).versionName } catch (e: Exception) { "" }
        find<TextView>(R.id.about).text = host.getString(R.string.about, version)
    }

    private fun onOff(on: Boolean) = host.getString(if (on) R.string.on else R.string.off)

    private fun row(title: Int, sub: Int, value: () -> String, action: () -> Unit): View {
        val v = LayoutInflater.from(host).inflate(R.layout.item_setting, rows, false)
        v.findViewById<TextView>(R.id.title).setText(title)
        v.findViewById<TextView>(R.id.sub).setText(sub)
        val valueView = v.findViewById<TextView>(R.id.value)
        valueView.text = value()
        v.setOnClickListener {
            action()
            valueView.text = value()
        }
        v.zoomOnFocus(1.02f)
        rows.addView(v)
        return v
    }

    override fun focusContent() {
        first.requestFocus()
    }
}
