package kr.co.fixlog.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import kr.co.fixlog.R
import kr.co.fixlog.model.FileItem
import kr.co.fixlog.model.FileType

/**
 * 문서 트리 리스트 어댑터.
 * 폴더/문서 아이템에 아이콘·이름·라벨(태그)·소유자·수정일을 표시하고, 우측 끝 더보기(⋮) 버튼을 제공한다.
 *
 * - 행 클릭: 폴더는 [onFolderClick](하위 이동), 문서는 [onFileClick](에디터 열기).
 * - 더보기 클릭: [onMoreClick](이름 변경/이동/삭제 팝업).
 */
class DocumentAdapter(
    private var items: List<FileItem> = emptyList(),
    private val onFolderClick: (FileItem) -> Unit,
    private val onFileClick: (FileItem) -> Unit,
    // 더보기 버튼 클릭. null이면 더보기 버튼 숨김.
    private val onMoreClick: ((FileItem) -> Unit)? = null,
    // 롱클릭(선택). CRUD 액션 진입점. null이면 비활성.
    private val onItemLongClick: ((FileItem) -> Unit)? = null
) : RecyclerView.Adapter<DocumentAdapter.DocumentViewHolder>() {

    /** 한 행에 표시할 최대 라벨 수(초과분은 +N 으로 축약). */
    private val maxLabels = 3

    fun updateItems(newItems: List<FileItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DocumentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_document, parent, false)
        return DocumentViewHolder(view)
    }

    override fun onBindViewHolder(holder: DocumentViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class DocumentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_icon)
        private val tvName: TextView = itemView.findViewById(R.id.tv_name)
        private val tvMeta: TextView = itemView.findViewById(R.id.tv_meta)
        private val llLabels: LinearLayout = itemView.findViewById(R.id.ll_labels)
        private val btnMore: ImageView = itemView.findViewById(R.id.btn_more)

        fun bind(item: FileItem) {
            tvName.text = item.name.ifBlank {
                if (item.type == FileType.FOLDER) itemView.context.getString(R.string.tree_untitled_folder) else itemView.context.getString(R.string.tree_untitled)
            }
            tvMeta.text = buildMeta(item)
            bindLabels(item.labels)

            when (item.type) {
                FileType.FOLDER -> {
                    ivIcon.setImageResource(R.drawable.ic_folder)
                    itemView.setOnClickListener { onFolderClick(item) }
                }
                FileType.FILE -> {
                    ivIcon.setImageResource(R.drawable.ic_file)
                    itemView.setOnClickListener { onFileClick(item) }
                }
            }

            val moreClick = onMoreClick
            if (moreClick != null) {
                btnMore.visibility = View.VISIBLE
                btnMore.setOnClickListener { moreClick(item) }
            } else {
                btnMore.visibility = View.GONE
                btnMore.setOnClickListener(null)
            }

            val longClick = onItemLongClick
            if (longClick != null) {
                itemView.setOnLongClickListener {
                    longClick(item)
                    true
                }
            } else {
                itemView.setOnLongClickListener(null)
            }
        }

        /** 소유자 · 수정일 형태로 메타를 만든다. 값이 없으면 있는 것만 표시. */
        private fun buildMeta(item: FileItem): String {
            val parts = buildList {
                item.owner?.takeIf { it.isNotBlank() }?.let { add(it) }
                item.date.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            return parts.joinToString(" · ").ifBlank { "—" }
        }

        /** 라벨 칩을 동적으로 채운다. 없으면 라벨 행을 숨긴다. */
        private fun bindLabels(labels: List<String>) {
            llLabels.removeAllViews()
            if (labels.isEmpty()) {
                llLabels.visibility = View.GONE
                return
            }
            llLabels.visibility = View.VISIBLE
            val ctx = llLabels.context
            val shown = labels.take(maxLabels)
            shown.forEachIndexed { index, label ->
                llLabels.addView(makeChip(label).also {
                    if (index > 0) (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(4)
                })
            }
            if (labels.size > shown.size) {
                llLabels.addView(makeChip("+${labels.size - shown.size}").also {
                    (it.layoutParams as LinearLayout.LayoutParams).marginStart = dp(4)
                })
            }
        }

        private fun makeChip(text: String): TextView {
            val ctx = llLabels.context
            return TextView(ctx).apply {
                this.text = text
                textSize = 11f
                setTextColor(ContextCompat.getColor(ctx, R.color.editor_meta))
                setBackgroundResource(R.drawable.bg_tag)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        }

        private fun dp(v: Int): Int =
            (v * itemView.resources.displayMetrics.density).toInt()
    }
}
