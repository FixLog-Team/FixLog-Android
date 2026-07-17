package kr.co.fixlog.data.remote.dto

/**
 * AI 질의응답(/ai/ask) 결과. 서버 com.fixlog.presentation.dto.response.AskResponse와 대응.
 *
 * - answer    : 참고 문서를 근거로 생성한 답변. 관련 문서가 없으면 안내 문구가 온다.
 * - references: 답변 근거로 사용된 문서 목록(빈 리스트 가능).
 */
data class AskResponse(
    val answer: String,
    val references: List<SearchResultDto>
)
