package kr.co.fixlog.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.data.remote.FolderApi
import kr.co.fixlog.model.FileItem
import kr.co.fixlog.model.FileType

/**
 * 공유(내가 공유한/공유받은) 리스트용 트리 어댑터.
 * 폴더는 좌측 ▶/▼ 토글로 펼쳐 하위 폴더·문서를 인라인 표시하고(지연 로드), 문서는 탭 시 열람 콜백.
 *
 * @param resolveOwner 소유자 userId → 표시 이름(없으면 null → 메타에서 소유자 생략).
 */
class SharedTreeAdapter(
    private val scope: CoroutineScope,
    private val resolveOwner: (String?) -> String?,
    private val onFileClick: (FileItem) -> Unit
) : RecyclerView.Adapter<SharedTreeAdapter.VH>() {

    /** 트리 노드. children은 펼칠 때 서버에서 로드한다. */
    class Node internal constructor(
        val item: FileItem,
        val depth: Int,
        var expanded: Boolean = false,
        var loaded: Boolean = false,
        var loading: Boolean = false,
        val children: MutableList<Node> = mutableListOf()
    )

    /** 루트 노드(최상위 공유 항목). */
    private val roots = mutableListOf<Node>()

    /** 화면에 보이는(펼쳐진) 노드의 평면 리스트. */
    private val visible = mutableListOf<Node>()

    /** 최상위 공유 폴더/문서로 트리를 초기화한다. */
    fun setRoots(items: List<FileItem>) {
        roots.clear()
        roots.addAll(items.map { Node(it, depth = 0) })
        rebuildVisible()
        notifyDataSetChanged()
    }

    private fun rebuildVisible() {
        visible.clear()
        fun add(nodes: List<Node>) {
            for (n in nodes) {
                visible.add(n)
                if (n.expanded) add(n.children)
            }
        }
        add(roots)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_shared_tree, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(visible[position])

    override fun getItemCount(): Int = visible.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val vIndent: View = itemView.findViewById(R.id.v_indent)
        private val ivToggle: ImageView = itemView.findViewById(R.id.iv_toggle)
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_icon)
        private val tvName: TextView = itemView.findViewById(R.id.tv_name)
        private val tvMeta: TextView = itemView.findViewById(R.id.tv_meta)

        fun bind(node: Node) {
            val item = node.item
            val isFolder = item.type == FileType.FOLDER
            val density = itemView.resources.displayMetrics.density
            vIndent.layoutParams = vIndent.layoutParams.apply { width = (node.depth * 16 * density).toInt() }

            tvName.text = item.name.ifBlank { if (isFolder) itemView.context.getString(R.string.tree_untitled_folder) else itemView.context.getString(R.string.tree_untitled) }
            tvMeta.text = buildMeta(item)
            tvMeta.visibility = if (tvMeta.text.isBlank()) View.GONE else View.VISIBLE
            ivIcon.setImageResource(if (isFolder) R.drawable.ic_folder else R.drawable.ic_file)

            if (isFolder) {
                ivToggle.visibility = View.VISIBLE
                // ▶(접힘, 0°) / ▼(펼침, 90°). ic_arrow_right = chevron-right.
                ivToggle.rotation = if (node.expanded) 90f else 0f
                itemView.setOnClickListener { toggle(node) }
            } else {
                ivToggle.visibility = View.INVISIBLE
                itemView.setOnClickListener { onFileClick(item) }
            }
        }

        private fun buildMeta(item: FileItem): String {
            // 소유자: 매핑으로 이름 해석 → 실패 시, 원본이 이미 사람 이름이면 그대로, UUID면 생략.
            val raw = item.owner
            val owner = resolveOwner(raw) ?: raw?.takeIf { !looksLikeId(it) }
            return listOfNotNull(owner?.takeIf { it.isNotBlank() }, item.date.takeIf { it.isNotBlank() })
                .joinToString(" · ")
        }

        /** UUID 등 식별자로 보이는 문자열인지(이름 대신 생략 판단용). */
        private fun looksLikeId(s: String): Boolean =
            s.length >= 20 && s.none { it == ' ' } && s.count { it == '-' } >= 3
    }

    /** 폴더 펼침/접힘. 처음 펼칠 때 하위를 서버에서 로드한다. */
    private fun toggle(node: Node) {
        if (node.item.type != FileType.FOLDER) return
        if (node.expanded) {
            node.expanded = false
            rebuildVisible()
            notifyDataSetChanged()
            return
        }
        node.expanded = true
        if (node.loaded || node.loading) {
            rebuildVisible()
            notifyDataSetChanged()
            return
        }
        node.loading = true
        scope.launch {
            val contents = runCatching { FolderApi.getFolderContents(node.item.id) }.getOrNull()
            node.loading = false
            node.loaded = true
            if (contents != null) {
                val childNodes = contents.folders.map {
                    Node(FileItem(it.folderId, it.folderName, FileType.FOLDER,
                        it.updateTime?.take(10).orEmpty(), owner = it.createUser), node.depth + 1)
                } + contents.documents.map {
                    Node(FileItem(it.documentId, it.title, FileType.FILE,
                        (it.updateTime ?: it.createTime)?.take(10).orEmpty(),
                        owner = it.createUserName ?: it.createUser), node.depth + 1)
                }
                node.children.clear()
                node.children.addAll(childNodes)
            }
            rebuildVisible()
            notifyDataSetChanged()
        }
    }
}
