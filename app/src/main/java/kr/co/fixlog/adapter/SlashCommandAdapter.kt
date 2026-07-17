package kr.co.fixlog.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kr.co.fixlog.R
import kr.co.fixlog.model.SlashCommand

class SlashCommandAdapter(
    private val onClick: (SlashCommand) -> Unit
) : RecyclerView.Adapter<SlashCommandAdapter.VH>() {

    private var items: List<SlashCommand> = emptyList()

    fun setItems(newItems: List<SlashCommand>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_slash_command, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tv_subtitle)

        fun bind(item: SlashCommand) {
            tvTitle.text = item.title
            tvSubtitle.text = item.subtitle
            itemView.setOnClickListener { onClick(item) }
        }
    }
}
