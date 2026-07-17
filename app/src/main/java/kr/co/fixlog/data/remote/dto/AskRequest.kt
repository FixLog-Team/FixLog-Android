package kr.co.fixlog.data.remote.dto

/**
 * AI 질의응답(/ai/ask) 요청. 서버 com.fixlog.presentation.dto.request.AskRequest와 대응.
 *
 * - question: 검색/질문 문장(필수, 최대 2000자).
 * - topK    : 참고 문서 개수(1~20). null이면 전송 생략 → 서버 기본값 5.
 */
data class AskRequest(
    val question: String,
    val topK: Int? = null
)
