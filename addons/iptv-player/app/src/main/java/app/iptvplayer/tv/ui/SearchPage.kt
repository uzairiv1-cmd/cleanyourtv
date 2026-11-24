package app.iptvplayer.tv.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.iptvplayer.tv.Item
import app.iptvplayer.tv.Kind
import app.iptvplayer.tv.R
import app.iptvplayer.tv.Section
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One search over every channel, movie and series of the playlist. */
class SearchPage(host: MainActivity) : Page(host, R.layout.page_search) {
    private val query: EditText = find(R.id.query)
    private val results: RecyclerView = find(R.id.results)
    private var found: List<Item> = emptyList()
    private var job: Job? = null

    private val adapter: SimpleAdapter<Item> = SimpleAdapter<Item>(R.layout.item_channel) { v, item, i ->
        v.findViewById<TextView>(R.id.number).visibility = View.GONE
        val img = v.findViewById<ImageView>(R.id.image)
        img.clipToBackground()
        ImageLoader.load(img, item.logo, MonogramDrawable(item.title), 120)
        v.findViewById<TextView>(R.id.title).text = item.title
        v.findViewById<TextView>(R.id.info).apply {
            visibility = View.VISIBLE
            setText(
                when (item.kind) {
                    Kind.LIVE -> R.string.kind_live
                    Kind.MOVIE -> R.string.kind_movie
                    Kind.SERIES -> R.string.kind_series
                    Kind.EPISODE -> R.string.kind_episode
                }
            )
        }
        v.findViewById<View>(R.id.star).visibility = if (host.isFavorite(item)) View.VISIBLE else View.GONE
        v.setOnClickListener { open(i) }
        v.setOnLongClickListener { host.toggleFavorite(item); adapter.notifyItemChanged(i); true }
        v.zoomOnFocus(1.02f)
    }

    init {
        results.layoutManager = LinearLayoutManager(host)
        results.adapter = adapter
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = schedule()
        })
        showMessage(host.getString(R.string.search_min))
    }

    override fun focusContent() {
        query.requestFocus()
    }

    override fun onBack(): Boolean {
        if (results.hasFocus()) { query.requestFocus(); return true }
        return false
    }

    private fun schedule() {
        job?.cancel()
        val q = query.text.toString().trim()
        if (q.length < 2) {
            adapter.submit(emptyList())
            busy(false)
            showMessage(host.getString(R.string.search_min))
            return
        }
        job = host.scope.launch {
            delay(450)
            busy(true)
            try {
                val out = ArrayList<Item>()
                for (section in listOf(Section.LIVE, Section.MOVIES, Section.SERIES)) {
                    val all = try { host.source.all(section) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
                    all.filterTo(out) { it.title.contains(q, ignoreCase = true) }
                    if (out.size > 300) break
                }
                found = out.take(300)
                busy(false)
                adapter.submit(found)
                showMessage(if (found.isEmpty()) host.getString(R.string.no_match, q) else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                busy(false)
                showMessage(e.message)
            }
        }
    }

    private fun open(i: Int) {
        val item = found.getOrNull(i) ?: return
        when (item.kind) {
            Kind.SERIES -> host.openSeries(item)
            Kind.LIVE -> {
                val live = found.filter { it.kind == Kind.LIVE }
                host.play(live, live.indexOf(item), host.getString(R.string.nav_search))
            }
            else -> host.play(listOf(item), 0, host.getString(R.string.nav_search))
        }
    }
}
