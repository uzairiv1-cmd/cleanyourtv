package app.iptvplayer.tv.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.iptvplayer.tv.R

/** One screen inside the main window, next to the side menu. */
abstract class Page(val host: MainActivity, layout: Int) {
    val view: View = LayoutInflater.from(host).inflate(layout, null, false)

    /** Called each time the page comes on screen. */
    open fun onShown() = Unit

    /** Called when the page leaves the screen or the app goes to the background. */
    open fun onHidden() = Unit

    /** True when the page handled Back itself. */
    open fun onBack(): Boolean = false

    /** Moves focus into the page, onto the item it remembers. */
    open fun focusContent() {
        view.requestFocus()
    }

    protected fun <T : View> find(id: Int): T = view.findViewById(id)

    private val progress: ProgressBar? get() = view.findViewById(R.id.progress)
    private val message: TextView? get() = view.findViewById(R.id.message)

    protected fun busy(on: Boolean) {
        progress?.visibility = if (on) View.VISIBLE else View.GONE
        if (on) message?.visibility = View.GONE
    }

    protected fun showMessage(text: String?) {
        message?.text = text
        message?.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}

/** A plain adapter: one layout, one bind function. */
class SimpleAdapter<T>(
    private val layout: Int,
    private val bind: (View, T, Int) -> Unit,
) : RecyclerView.Adapter<SimpleAdapter.Holder>() {
    class Holder(v: View) : RecyclerView.ViewHolder(v)

    var items: List<T> = emptyList()
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<T>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(layout, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = bind(holder.itemView, items[position], position)
}

/** Focuses the item at [position] once the list has laid it out (a poster's focusable card if it has one). */
fun RecyclerView.focusPosition(position: Int) {
    scrollToPosition(position)
    post {
        val v = layoutManager?.findViewByPosition(position)
        when {
            v == null -> requestFocus()
            v.isFocusable -> v.requestFocus()
            else -> (v.findViewById<View>(R.id.card) ?: v).requestFocus()
        }
    }
}
