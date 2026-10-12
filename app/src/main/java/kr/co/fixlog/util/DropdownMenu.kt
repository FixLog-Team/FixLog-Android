package kr.co.fixlog.util

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import kr.co.fixlog.R

/**
 * 버튼 아래에 붙는 앱 스타일 드롭다운 메뉴(시스템 PopupMenu 대체).
 * 둥근 카드(표면색·옅은 테두리·그림자) 안에 아이콘 + 라벨 행을 그리고, 앵커의 오른쪽 끝에 맞춰 연다.
 * [Item.checked]가 null이 아니면 선택형 항목: 켜짐일 때 아이콘/라벨을 파랗게 하고 우측에 체크를 표시한다.
 */
object DropdownMenu {

    class Item(val label: String, val iconRes: Int, val checked: Boolean? = null, val onClick: () -> Unit)

    fun show(anchor: View, items: List<Item>) {
        val ctx = anchor.context
        val density = ctx.resources.displayMetrics.density
        val blue = ContextCompat.getColor(ctx, R.color.editor_blue)
        val iconColor = ContextCompat.getColor(ctx, R.color.icon_dark)
        val textColor = ContextCompat.getColor(ctx, R.color.editor_title)

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pv = (6 * density).toInt()
            setPadding(0, pv, 0, pv)
        }
        val popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(ctx, R.drawable.bg_popup_menu))
            elevation = 8 * density
            isOutsideTouchable = true
        }

        val inflater = LayoutInflater.from(ctx)
        items.forEach { item ->
            val row = inflater.inflate(R.layout.item_popup_menu, content, false)
            val on = item.checked == true
            row.findViewById<ImageView>(R.id.iv_icon).apply {
                setImageResource(item.iconRes)
                imageTintList = ColorStateList.valueOf(if (on) blue else iconColor)
            }
            row.findViewById<TextView>(R.id.tv_label).apply {
                text = item.label
                setTextColor(if (on) blue else textColor)
            }
            row.findViewById<ImageView>(R.id.iv_check).visibility = if (on) View.VISIBLE else View.GONE
            row.setOnClickListener {
                popup.dismiss()
                item.onClick()
            }
            content.addView(row)
        }

        popup.showAsDropDown(anchor, 0, (4 * density).toInt(), Gravity.END)
    }
}
