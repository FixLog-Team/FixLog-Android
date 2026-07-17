package kr.co.fixlog.data.remote.dto

/**
 * 서버 공통 응답 래퍼. com.fixlog.common.response.Response / DataResponse를 통합한 형태.
 *
 * - code: 서버 Code enum이 JSON 직렬화 시 문자열로 떨어진다 (예: "SUCCESS", "UNAUTHORIZED")
 * - message: 실패 시 사유
 * - result: 성공 시 페이로드. 단순 Response(데이터 없음)면 null
 *
 * isSuccess()로 성공 여부 판단. result가 null이어도 성공일 수 있으므로 두 값을 분리.
 */
data class ApiResponse<T>(
    val code: String?,
    val message: String?,
    val result: T?
) {
    fun isSuccess(): Boolean = code == "SUCCESS"
}
