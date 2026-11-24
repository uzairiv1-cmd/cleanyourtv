package app.iptvplayer.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.iptvplayer.tv.Category
import app.iptvplayer.tv.Item
import app.iptvplayer.tv.R

class CategoryAdapter(
    private val onClick: (Category) -> Unit,
    private val onFocus: (Category) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.Holder>() {
    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

    var items: List<Category> = emptyList()
        private set
    var selectedId: String? = null
        set(value) {
            val old = items.indexOfFirst { it.id == field }
            field = value
            if (old >= 0) notifyItemChanged(old)
            val now = items.indexOfFirst { it.id == value }
            if (now >= 0) notifyItemChanged(now)
        }

    fun indexOfSelected() = items.indexOfFirst { it.id == selectedId }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Category>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.row_category, parent, false) as TextView)

    override fun onBindViewHolder(h: Holder, position: Int) {
        val c = items[position]
        h.text.text = c.name
        h.text.isActivated = c.id == selectedId
        h.text.setOnClickListener { onClick(c) }
        h.text.setOnFocusChangeListener { _, has -> if (has) onFocus(c) }
    }
}

class ItemAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
) : RecyclerView.Adapter<ItemAdapter.Holder>() {
    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.image)
        val title: TextView = v.findViewById(R.id.title)
        val info: TextView? = v.findViewById(R.id.info)
    }

    var items: List<Item> = emptyList()
        private set
    var posters = false
        private set
    var favoriteIds: Set<String> = emptySet()

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Item>, posters: Boolean) {
        items = list
        this.posters = posters
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = if (posters) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (viewType == 1) R.layout.cell_poster else R.layout.row_item
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        if (viewType == 1) {
            v.setOnFocusChangeListener { view, has ->
                val s = if (has) 1.07f else 1f
                view.animate().scaleX(s).scaleY(s).setDuration(120).start()
                view.elevation = if (has) 8f else 0f
            }
        }
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val item = items[position]
        val star = if (item.kind.name + ":" + item.id in favoriteIds) "★ " else ""
        h.title.text = star + item.title
        h.info?.text = item.info
        h.info?.visibility = if (item.info.isEmpty()) View.GONE else View.VISIBLE
        val px = if (posters) 320 else 120
        ImageLoader.load(h.image, item.logo, if (posters) R.drawable.placeholder_poster else R.drawable.placeholder_logo, px)
        h.itemView.setOnClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onClick) }
        h.itemView.setOnLongClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onLongClick); true }
    }
}
