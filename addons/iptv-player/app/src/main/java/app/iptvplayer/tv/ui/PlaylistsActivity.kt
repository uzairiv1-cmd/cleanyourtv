package app.iptvplayer.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import app.iptvplayer.tv.R
import app.iptvplayer.tv.SourceType
import app.iptvplayer.tv.data.Store

/** The saved playlists: OK opens one, and a menu offers Edit and Delete. */
class PlaylistsActivity : Activity() {
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlists)
        list = findViewById(R.id.list)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        list.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val active = Store.active(this)?.id
        fun row(text: String, sub: String, onClick: () -> Unit): TextView {
            val v = inflater.inflate(R.layout.row_playlist, list, false) as TextView
            v.text = if (sub.isEmpty()) text else "$text\n$sub"
            v.setOnClickListener { onClick() }
            list.addView(v)
            return v
        }
        row(getString(R.string.add_playlist), "") { startActivity(Intent(this, PlaylistEditActivity::class.java)) }
        var focus: TextView? = null
        for (p in Store.playlists(this)) {
            val kind = if (p.type == SourceType.XTREAM) getString(R.string.type_xtream) else getString(R.string.type_m3u)
            val mark = if (p.id == active) "  ✓" else ""
            val v = row(p.name + mark, kind) {
                AlertDialog.Builder(this)
                    .setTitle(p.name)
                    .setItems(arrayOf(getString(R.string.open), getString(R.string.edit), getString(R.string.delete))) { _, which ->
                        when (which) {
                            0 -> { Store.setActive(this, p.id); finish() }
                            1 -> startActivity(Intent(this, PlaylistEditActivity::class.java).putExtra(PlaylistEditActivity.EXTRA_ID, p.id))
                            2 -> AlertDialog.Builder(this)
                                .setMessage(getString(R.string.delete_confirm, p.name))
                                .setPositiveButton(R.string.delete) { _, _ -> Store.delete(this, p.id); render() }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                    .show()
            }
            if (p.id == active) focus = v
        }
        (focus ?: list.getChildAt(0))?.requestFocus()
    }
}
