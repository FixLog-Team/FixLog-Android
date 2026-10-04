package kr.co.fixlog.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kr.co.fixlog.R
import kr.co.fixlog.data.remote.dto.TrashItemDto

/**
 * 휴지통 목록 어댑터. 폴더/문서 아이콘 + 이름 + 삭제일 + 복원/영구삭제 버튼.
 */
class TrashAdapter(
    private var items: List<TrashItemDto> = emptyList(),
    private val onRestore: (TrashItemDto) -> Unit,
    private val onPurge: (TrashItemDto) -> Unit
) : RecyclerView.Adapter<TrashAdapter.VH>() {

    fun update(newItems: List<TrashItemDto>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_trash, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_icon)
        private val tvName: TextView = itemView.findViewById(R.id.tv_name)
        private val tvMeta: TextView = itemView.findViewById(R.id.tv_meta)
        private val btnRestore: ImageView = itemView.findViewById(R.id.btn_restore)
        private val btnPurge: ImageView = itemView.findViewById(R.id.btn_purge)

        fun bind(item: TrashItemDto) {
            val isFolder = item.resourceType.equals("FOLDER", ignoreCase = true)
            ivIcon.setImageResource(if (isFolder) R.drawable.ic_folder else R.drawable.ic_file)
            tvName.text = item.name?.ifBlank { null } ?: if (isFolder) itemView.context.getString(R.string.tree_untitled_folder) else itemView.context.getString(R.string.tree_untitled)
            val typeLabel = if (isFolder) itemView.context.getString(R.string.tree_folder) else itemView.context.getString(R.string.tree_document)
            val date = item.deletedAt?.take(10).orEmpty()
            tvMeta.text = listOf(typeLabel, date).filter { it.isNotBlank() }.joinToString(" · ")
            btnRestore.setOnClickListener { onRestore(item) }
            btnPurge.setOnClickListener { onPurge(item) }
        }
    }
}
