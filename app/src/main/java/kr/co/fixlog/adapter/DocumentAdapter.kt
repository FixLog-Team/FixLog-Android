package kr.co.fixlog.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kr.co.fixlog.R
import kr.co.fixlog.model.FileItem
import kr.co.fixlog.model.FileType

/**
 * Adapter for the main document list RecyclerView.
 * Displays folder and file items with appropriate icons and click behavior.
 */
class DocumentAdapter(
    private var items: List<FileItem> = emptyList(),
    private val onFolderClick: (FileItem) -> Unit,
    private val onFileClick: (FileItem) -> Unit,
    // 롱클릭 시 폴더/파일 공통으로 호출. CRUD 액션 메뉴 진입점. null이면 롱클릭 비활성.
    private val onItemLongClick: ((FileItem) -> Unit)? = null
) : RecyclerView.Adapter<DocumentAdapter.DocumentViewHolder>() {

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
        private val tvDate: TextView = itemView.findViewById(R.id.tv_date)
        private val ivArrow: ImageView = itemView.findViewById(R.id.iv_arrow)

        fun bind(item: FileItem) {
            tvName.text = item.name
            tvDate.text = item.date

            // Set icon and arrow visibility based on type
            when (item.type) {
                FileType.FOLDER -> {
                    ivIcon.setImageResource(R.drawable.ic_folder)
                    ivArrow.visibility = View.VISIBLE
                    itemView.setOnClickListener { onFolderClick(item) }
                }
                FileType.FILE -> {
                    ivIcon.setImageResource(R.drawable.ic_file)
                    ivArrow.visibility = View.GONE
                    itemView.setOnClickListener { onFileClick(item) }
                }
            }

            // 롱클릭 → CRUD 액션 메뉴(호출자 제공). 소비 여부는 콜백 등록 여부로 결정.
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
    }
}