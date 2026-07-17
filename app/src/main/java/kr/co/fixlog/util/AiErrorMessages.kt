package kr.co.fixlog.util

import android.content.Context
import kr.co.fixlog.R
import kr.co.fixlog.data.remote.AiException

/**
 * AI 호출 실패 [Throwable]을 사용자용 문구로 변환한다.
 * [AiException]의 각 사유를 구분해 안내하고, 그 외 예기치 못한 오류는 일반 문구로 처리한다.
 */
fun Throwable.toAiMessage(context: Context): String {
    val res = when (this) {
        is AiException.Timeout -> R.string.ai_error_timeout
        is AiException.Network -> R.string.ai_error_network
        is AiException.Unauthorized -> R.string.ai_error_unauthorized
        is AiException.NotFound -> R.string.ai_error_not_found
        is AiException.InvalidRequest -> R.string.ai_error_invalid_request
        is AiException.Server -> R.string.ai_error_server
        is AiException.Empty -> R.string.ai_error_empty
        else -> R.string.ai_error_unknown
    }
    return context.getString(res)
}