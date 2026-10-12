package kr.co.fixlog.util

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import kr.co.fixlog.R

/**
 * 한 줄 입력 팝업(이름 변경/새 폴더/워크스페이스 추가 등)의 앱 스타일 입력창.
 * [view]를 AlertDialog.setView에 넣고, 다이얼로그 생성 후 [showWithKeyboard]로 띄우면
 * 입력창에 포커스 + 키보드가 바로 올라온다.
 */
class DialogInput(context: Context, hint: String? = null, text: String? = null) {

    val view: View = LayoutInflater.from(context).inflate(R.layout.view_dialog_input, null)
    val editText: EditText = view.findViewById(R.id.et_input)

    init {
        hint?.let { editText.hint = it }
        text?.let {
            editText.setText(it)
            editText.setSelection(it.length)
        }
    }

    /** 입력값(앞뒤 공백 제거). */
    val value: String get() = editText.text?.toString()?.trim().orEmpty()

    /** 다이얼로그를 띄우면서 입력창에 포커스를 주고 키보드를 연다. */
    fun showWithKeyboard(dialog: AlertDialog) {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        editText.requestFocus()
    }
}
