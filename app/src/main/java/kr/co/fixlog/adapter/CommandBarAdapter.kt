package kr.co.fixlog.adapter

import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kr.co.fixlog.R

/**
 * 키보드 위 커맨드 바(가로) 어댑터.
 * - 기본 목록: 블록 변환/삽입 커맨드 + "글자 수정"(서식 목록으로 전환) + "들여쓰기"/"내어쓰기".
 * - 서식 목록: "뒤로가기"(기본 목록 복귀) + 굵게/기울임/밑줄/취소선/인라인 코드.
 * 목록 전환은 어댑터 내부에서 처리하고, 실제 동작은 [onClick]으로 위임한다.
 * BLOCK의 id는 editor.html의 window.executeSlashCommand(id), STYLE의 id는 window.toggleStyle(id)와 동일하다.
 */
class CommandBarAdapter(
    private val onClick: (Cmd) -> Unit
) : RecyclerView.Adapter<CommandBarAdapter.VH>() {

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) { recyclerView = rv }
    override fun onDetachedFromRecyclerView(rv: RecyclerView) { recyclerView = null }

    enum class Kind { BLOCK, STYLE, INDENT, OUTDENT, OPEN_STYLES, BACK }

    /** (커맨드 id, 종류, 라벨 리소스, 아이콘 리소스). 아이콘 0이면 숨김. */
    data class Cmd(val id: String, val kind: Kind, val labelRes: Int, val iconRes: Int)

    private val mainItems: List<Cmd> = listOf(
        Cmd("paragraph", Kind.BLOCK, R.string.cmd_text, R.drawable.ic_file),
        Cmd("textStyle", Kind.OPEN_STYLES, R.string.cmd_text_style, R.drawable.ic_edit),
        Cmd("heading1", Kind.BLOCK, R.string.cmd_h1, R.drawable.ic_edit),
        Cmd("heading2", Kind.BLOCK, R.string.cmd_h2, R.drawable.ic_edit),
        Cmd("heading3", Kind.BLOCK, R.string.cmd_h3, R.drawable.ic_edit),
        Cmd("bulletList", Kind.BLOCK, R.string.cmd_bulleted, R.drawable.ic_more_horiz),
        Cmd("numberedList", Kind.BLOCK, R.string.cmd_numbered, R.drawable.ic_more_horiz),
        Cmd("checkList", Kind.BLOCK, R.string.cmd_check, R.drawable.ic_arrow_right),
        Cmd("indent", Kind.INDENT, R.string.cmd_indent, R.drawable.ic_arrow_right),
        Cmd("outdent", Kind.OUTDENT, R.string.cmd_outdent, R.drawable.ic_chevron_left),
        Cmd("callout", Kind.BLOCK, R.string.cmd_callout, R.drawable.ic_sparkle),
        Cmd("codeBlock", Kind.BLOCK, R.string.cmd_code, R.drawable.ic_grid),
        Cmd("table", Kind.BLOCK, R.string.cmd_table, R.drawable.ic_grid),
    )

    private val styleItems: List<Cmd> = listOf(
        Cmd("back", Kind.BACK, R.string.cmd_back, R.drawable.ic_chevron_left),
        Cmd("bold", Kind.STYLE, R.string.cmd_bold, 0),
        Cmd("italic", Kind.STYLE, R.string.cmd_italic, 0),
        Cmd("underline", Kind.STYLE, R.string.cmd_underline, 0),
        Cmd("strike", Kind.STYLE, R.string.cmd_strike, 0),
        Cmd("code", Kind.STYLE, R.string.cmd_inline_code, 0),
    )

    private var items: List<Cmd> = mainItems

    /** 기본 목록으로 되돌린다(키보드가 내려갈 때 등). */
    fun showMain() = switchTo(mainItems)

    @SuppressLint("NotifyDataSetChanged")
    private fun switchTo(next: List<Cmd>) {
        if (items === next) return
        items = next
        notifyDataSetChanged()
        recyclerView?.scrollToPosition(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_command_chip, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.iv_cmd_icon)
        private val label: TextView = itemView.findViewById(R.id.tv_cmd_label)

        fun bind(cmd: Cmd) {
            label.setText(cmd.labelRes)
            applyLabelStyle(cmd)
            if (cmd.iconRes != 0) {
                icon.visibility = View.VISIBLE
                icon.setImageResource(cmd.iconRes)
            } else {
                icon.visibility = View.GONE
            }
            itemView.setOnClickListener {
                when (cmd.kind) {
                    Kind.OPEN_STYLES -> switchTo(styleItems)
                    Kind.BACK -> switchTo(mainItems)
                    else -> onClick(cmd)
                }
            }
        }

        /** 서식 칩은 라벨 자체를 해당 서식으로 표시한다(굵게는 굵게 등). 재사용 뷰라 매번 초기화. */
        private fun applyLabelStyle(cmd: Cmd) {
            val style = when (cmd.id) {
                "bold" -> Typeface.BOLD
                "italic" -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            label.typeface = if (cmd.id == "code") Typeface.MONOSPACE else Typeface.create(Typeface.DEFAULT, style)
            var flags = label.paintFlags and (Paint.UNDERLINE_TEXT_FLAG or Paint.STRIKE_THRU_TEXT_FLAG).inv()
            if (cmd.id == "underline") flags = flags or Paint.UNDERLINE_TEXT_FLAG
            if (cmd.id == "strike") flags = flags or Paint.STRIKE_THRU_TEXT_FLAG
            label.paintFlags = flags
        }
    }
}
