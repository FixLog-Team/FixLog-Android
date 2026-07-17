package kr.co.fixlog.util

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import java.util.WeakHashMap

/**
 * 백엔드 조회(목록/문서 조회 등) 중 화면 중앙에 원형 로딩 인디케이터 팝업을 띄우는 공용 헬퍼.
 *
 * - 화면(Context)당 하나의 다이얼로그를 재사용하며, 동시에 여러 조회가 진행돼도
 *   참조 카운트로 한 번만 표시하고 마지막 조회가 끝날 때 해제한다.
 * - 취소 불가(터치/뒤로가기로 닫히지 않음).
 */
object LoadingIndicator {

    private class Holder(val dialog: Dialog, var count: Int)

    private val holders = WeakHashMap<Context, Holder>()

    /** 로딩 팝업 표시(참조 카운트 +1). 이미 떠 있으면 카운트만 올린다. */
    fun show(context: Context) {
        holders[context]?.let { it.count++; return }
        val dialog = build(context)
        holders[context] = Holder(dialog, 1)
        runCatching { dialog.show() }
    }

    /** 로딩 팝업 해제(참조 카운트 -1). 0이 되면 실제로 닫는다. */
    fun hide(context: Context) {
        val holder = holders[context] ?: return
        holder.count--
        if (holder.count <= 0) {
            holders.remove(context)
            runCatching { holder.dialog.dismiss() }
        }
    }

    private fun build(context: Context): Dialog {
        val view = LayoutInflater.from(context).inflate(R.layout.view_loading, null)
        return Dialog(context).apply {
            setContentView(view)
            setCancelable(false)
            // 카드만 보이고 뒤는 은은하게 어둡게(기본 dim) 처리.
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }
}

/**
 * 로딩 인디케이터를 띄운 채 [block]을 실행하고, 성공/실패와 무관하게 끝나면 해제한다.
 * 기존 `lifecycleScope.launch { ... }`를 `launchWithLoading { ... }`로 바꾸면 된다.
 */
fun AppCompatActivity.launchWithLoading(block: suspend () -> Unit): Job =
    lifecycleScope.launch {
        LoadingIndicator.show(this@launchWithLoading)
        try {
            block()
        } finally {
            LoadingIndicator.hide(this@launchWithLoading)
        }
    }