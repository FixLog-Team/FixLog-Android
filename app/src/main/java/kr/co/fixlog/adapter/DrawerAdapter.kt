package kr.co.fixlog.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kr.co.fixlog.R
import kr.co.fixlog.model.DrawerMenuIconType
import kr.co.fixlog.model.DrawerMenuItem
import kr.co.fixlog.model.DrawerSection

/**
 * Adapter for the drawer menu RecyclerView.
 * Supports two view types: section headers and menu items.
 * Dynamic sections can be updated via [updateSections].
 */
class DrawerAdapter(
    private val onMenuItemClick: (DrawerMenuItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1
    }

    // Flattened list of section headers and menu items
    private val displayItems = mutableListOf<DrawerDisplayItem>()

    /** Update drawer with new sections */
    fun updateSections(sections: List<DrawerSection>) {
        displayItems.clear()
        for (section in sections) {
            displayItems.add(DrawerDisplayItem.Header(section.title))
            for (menuItem in section.items) {
                displayItems.add(DrawerDisplayItem.Item(menuItem))
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (displayItems[position]) {
            is DrawerDisplayItem.Header -> VIEW_TYPE_HEADER
            is DrawerDisplayItem.Item -> VIEW_TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_drawer_section_header, parent, false)
                SectionHeaderViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_drawer_menu, parent, false)
                MenuItemViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val displayItem = displayItems[position]) {
            is DrawerDisplayItem.Header -> {
                (holder as SectionHeaderViewHolder).bind(displayItem.title)
            }
            is DrawerDisplayItem.Item -> {
                (holder as MenuItemViewHolder).bind(displayItem.menuItem)
            }
        }
    }

    override fun getItemCount(): Int = displayItems.size

    inner class SectionHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_section_title)

        fun bind(title: String) {
            tvTitle.text = title
        }
    }

    inner class MenuItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_menu_icon)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_menu_title)

        fun bind(menuItem: DrawerMenuItem) {
            tvTitle.text = menuItem.title

            // Apply custom text color if specified (e.g., red for Logout)
            menuItem.textColor?.let { tvTitle.setTextColor(it) }
                ?: tvTitle.setTextColor(0xFF333333.toInt())

            // Set icon based on type
            when (menuItem.iconType) {
                DrawerMenuIconType.FOLDER -> {
                    ivIcon.setImageResource(R.drawable.ic_folder)
                    ivIcon.visibility = View.VISIBLE
                }
                DrawerMenuIconType.FILE -> {
                    ivIcon.setImageResource(R.drawable.ic_file)
                    ivIcon.visibility = View.VISIBLE
                }
                DrawerMenuIconType.NONE -> {
                    ivIcon.visibility = View.GONE
                }
            }

            itemView.setOnClickListener { onMenuItemClick(menuItem) }
        }
    }

    /** Sealed class for flattened display items */
    private sealed class DrawerDisplayItem {
        data class Header(val title: String) : DrawerDisplayItem()
        data class Item(val menuItem: DrawerMenuItem) : DrawerDisplayItem()
    }
}