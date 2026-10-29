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
 * 휴지통 목록 어댑터. [들여쓰기][▶/▼ 토글][아이콘] 이름 + 삭제일 + 복원/영구삭제 버튼.
 * trashedParentId 로 트리(함께 삭제된 하위 항목을 폴더 아래 중첩)를 만들고, 각 레벨은 서버 정렬(최근 삭제순)을 유지한다.
 */
class TrashAdapter(
    private val onRestore: (TrashItemDto) -> Unit,
    private val onPurge: (TrashItemDto) -> Unit
) : RecyclerView.Adapter<TrashAdapter.VH>() {

    class Row(val item: TrashItemDto, val depth: Int, val hasChildren: Boolean)

    private var children: Map<String?, List<TrashItemDto>> = emptyMap()
    private var rows: List<Row> = emptyList()
    /** 접힌 폴더 id. 기본은 모두 펼침. */
    private val collapsed = mutableSetOf<String>()

    fun update(newItems: List<TrashItemDto>) {
        val ids = newItems.mapTo(HashSet()) { it.resourceId }
        // 부모가 목록에 없으면(권한 밖 등) 최상위로 취급
        children = newItems.groupBy { p -> p.trashedParentId?.takeIf { it in ids } }
        rebuild()
    }

    private fun rebuild() {
        val out = mutableListOf<Row>()
        fun walk(parentId: String?, depth: Int) {
            for (item in children[parentId].orEmpty()) {
                val hasChildren = !children[item.resourceId].isNullOrEmpty()
                out.add(Row(item, depth, hasChildren))
                if (hasChildren && item.resourceId !in collapsed) walk(item.resourceId, depth + 1)
            }
        }
        walk(null, 0)
        rows = out
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_trash, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(rows[position])

    override fun getItemCount(): Int = rows.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val vIndent: View = itemView.findViewById(R.id.v_indent)
        private val ivToggle: ImageView = itemView.findViewById(R.id.iv_toggle)
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_icon)
        private val tvName: TextView = itemView.findViewById(R.id.tv_name)
        private val tvMeta: TextView = itemView.findViewById(R.id.tv_meta)
        private val btnRestore: ImageView = itemView.findViewById(R.id.btn_restore)
        private val btnPurge: ImageView = itemView.findViewById(R.id.btn_purge)

        fun bind(row: Row) {
            val item = row.item
            val density = itemView.resources.displayMetrics.density
            vIndent.layoutParams = vIndent.layoutParams.apply { width = (row.depth * 24 * density).toInt() }
            ivToggle.visibility = if (row.hasChildren) View.VISIBLE else View.INVISIBLE
            // ▶(접힘, 0°) / ▼(펼침, 90°)
            ivToggle.rotation = if (item.resourceId in collapsed) 0f else 90f
            ivToggle.setOnClickListener {
                if (!collapsed.remove(item.resourceId)) collapsed.add(item.resourceId)
                rebuild()
            }
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
