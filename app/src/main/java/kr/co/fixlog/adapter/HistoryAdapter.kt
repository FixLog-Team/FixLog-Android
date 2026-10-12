package kr.co.fixlog.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kr.co.fixlog.R

/**
 * 버전 기록 목록 어댑터. 구획 제목(현재/이전 버전·안내 문구)과 버전 카드 두 종류의 행을 그린다.
 */
class HistoryAdapter(
    private val onClick: (Row.Version) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed interface Row {
        /** 구획 제목 또는 빈/오류 안내 문구. */
        data class Section(val text: String) : Row

        /**
         * 버전 카드. historyId == null 이면 현재 버전(문서 최신본).
         * @param chip 좌측 칩 문구(v3 / 현재)
         * @param badge 제목 옆 배지(제목 변경 등). null이면 숨김.
         */
        data class Version(
            val historyId: String?,
            val versionNo: Int,
            val chip: String,
            val title: String,
            val meta: String,
            val badge: String?,
            val createTime: String?
        ) : Row
    }

    private var rows: List<Row> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(next: List<Row>) {
        rows = next
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = if (rows[position] is Row.Section) TYPE_SECTION else TYPE_VERSION

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SECTION) {
            object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_history_section, parent, false)) {}
        } else {
            VersionVH(inflater.inflate(R.layout.item_history_version, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Section -> (holder.itemView as TextView).text = row.text
            is Row.Version -> (holder as VersionVH).bind(row)
        }
    }

    private inner class VersionVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvVersion: TextView = itemView.findViewById(R.id.tv_version)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
        private val tvBadge: TextView = itemView.findViewById(R.id.tv_badge)
        private val tvMeta: TextView = itemView.findViewById(R.id.tv_meta)

        fun bind(row: Row.Version) {
            tvVersion.text = row.chip
            tvTitle.text = row.title
            tvMeta.text = row.meta
            tvBadge.visibility = if (row.badge != null) View.VISIBLE else View.GONE
            tvBadge.text = row.badge
            itemView.setOnClickListener { onClick(row) }
        }
    }

    private companion object {
        const val TYPE_SECTION = 0
        const val TYPE_VERSION = 1
    }
}
