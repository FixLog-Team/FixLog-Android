package kr.co.fixlog.util

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kr.co.fixlog.R

/**
 * 더보기(…) 메뉴 팝업(문서 목록/문서 작성 공용): 각 항목을 아이콘 + 라벨로 보여준다.
 * [Action.danger]인 항목(삭제)은 빨간색으로 표시한다.
 * [titleIconRes]를 주면 제목 왼쪽에 문서/폴더 아이콘을 함께 표시한다.
 */
object ActionMenuDialog {

    class Action(val label: String, val iconRes: Int, val danger: Boolean = false, val run: () -> Unit)

    fun show(activity: AppCompatActivity, title: String, actions: List<Action>, titleIconRes: Int = 0) {
        val normalIcon = ContextCompat.getColor(activity, R.color.icon_dark)
        val normalText = ContextCompat.getColor(activity, R.color.editor_title)
        val danger = ContextCompat.getColor(activity, R.color.danger_red)

        val adapter = object : BaseAdapter() {
            override fun getCount() = actions.size
            override fun getItem(position: Int) = actions[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_action_menu, parent, false)
                val a = actions[position]
                v.findViewById<ImageView>(R.id.iv_icon).apply {
                    setImageResource(a.iconRes)
                    imageTintList = ColorStateList.valueOf(if (a.danger) danger else normalIcon)
                }
                v.findViewById<TextView>(R.id.tv_label).apply {
                    text = a.label
                    setTextColor(if (a.danger) danger else normalText)
                }
                return v
            }
        }

        val builder = AlertDialog.Builder(activity)
            .setAdapter(adapter) { _, which -> actions[which].run() }
        if (titleIconRes != 0) {
            val titleView = LayoutInflater.from(activity).inflate(R.layout.view_dialog_title_icon, null)
            titleView.findViewById<ImageView>(R.id.iv_title_icon).setImageResource(titleIconRes)
            titleView.findViewById<TextView>(R.id.tv_title).text = title
            builder.setCustomTitle(titleView)
        } else {
            builder.setTitle(title)
        }
        builder.show()
    }
}
