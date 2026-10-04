package kr.co.fixlog.util

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.data.remote.DocumentApi
import kr.co.fixlog.data.remote.dto.LabelDto

/**
 * 문서 라벨 편집 다이얼로그. 현재 라벨 목록(각 항목 X로 삭제) + 새 라벨 입력/추가.
 *  - 조회/추가/삭제: DocumentApi.getLabelsFull / addLabel / removeLabel
 *  - 닫을 때 [onChanged]로 에디터 헤더 라벨을 새로고침한다.
 */
object LabelEditorHelper {

    fun show(activity: AppCompatActivity, scope: CoroutineScope, documentId: String, onChanged: () -> Unit) {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val input = EditText(activity).apply {
            hint = activity.getString(R.string.label_label_name)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val addBtn = MaterialButton(activity).apply { text = activity.getString(R.string.common_add) }
        val inputRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(addBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8) })
        }
        val listContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val emptyView = TextView(activity).apply {
            text = activity.getString(R.string.label_no_labels)
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.editor_meta))
            setPadding(0, px(8), 0, 0)
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), px(4))
            addView(inputRow)
            addView(ScrollView(activity).apply {
                addView(listContainer)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(220)).apply { topMargin = px(8) })
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.label_labels))
            .setView(root)
            .setPositiveButton(activity.getString(R.string.common_close), null)
            .setOnDismissListener { onChanged() }
            .create()

        fun render(labels: List<LabelDto>) {
            listContainer.removeAllViews()
            if (labels.isEmpty()) {
                listContainer.addView(emptyView)
                return
            }
            labels.forEach { label ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, px(6), 0, px(6))
                }
                val name = TextView(activity).apply {
                    text = "# ${label.labelName.orEmpty()}"
                    textSize = 15f
                    setTextColor(ContextCompat.getColor(activity, R.color.editor_title))
                }
                val del = ImageView(activity).apply {
                    setImageResource(R.drawable.ic_close)
                    setColorFilter(ContextCompat.getColor(activity, R.color.editor_meta))
                    val p = px(6); setPadding(p, p, p, p)
                    setOnClickListener {
                        val id = label.labelId ?: return@setOnClickListener
                        scope.launch {
                            runCatching { DocumentApi.removeLabel(documentId, id) }
                                .onSuccess { reload(activity, scope, documentId, ::render) }
                                .onFailure { Toast.makeText(activity, activity.getString(R.string.label_delete_failed), Toast.LENGTH_SHORT).show() }
                        }
                    }
                }
                row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(del, LinearLayout.LayoutParams(px(34), px(34)))
                listContainer.addView(row)
            }
        }

        addBtn.setOnClickListener {
            val name = input.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                Toast.makeText(activity, activity.getString(R.string.label_enter_name), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            scope.launch {
                runCatching { DocumentApi.addLabel(documentId, name) }
                    .onSuccess {
                        input.setText("")
                        reload(activity, scope, documentId, ::render)
                    }
                    .onFailure { Toast.makeText(activity, activity.getString(R.string.label_add_failed), Toast.LENGTH_SHORT).show() }
            }
        }

        dialog.show()
        reload(activity, scope, documentId, ::render)
    }

    private fun reload(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        documentId: String,
        render: (List<LabelDto>) -> Unit
    ) {
        scope.launch {
            val labels = runCatching { DocumentApi.getLabelsFull(documentId) }.getOrDefault(emptyList())
            render(labels)
        }
    }
}
