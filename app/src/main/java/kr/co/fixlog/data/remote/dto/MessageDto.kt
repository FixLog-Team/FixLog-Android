package kr.co.fixlog.data.remote.dto

/**
 * AI 대화방 메시지. 서버 응답 필드가 확정적이지 않아(§6 주의) 모두 nullable + 기본값으로 둔다.
 *
 * - role       : USER | ASSISTANT
 * - references : 답변 근거 문서(어시스턴트 메시지에만). 없으면 null/빈 리스트.
 */
data class MessageDto(
    val messageId: String? = null,
    val role: String? = null,
    val content: String? = null,
    val status: String? = null,
    val sequence: Long? = null,
    val references: List<SearchResultDto>? = null
) {
    val isUser: Boolean get() = role.equals("USER", ignoreCase = true)
}
