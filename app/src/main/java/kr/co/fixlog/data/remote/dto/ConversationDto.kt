package kr.co.fixlog.data.remote.dto

/**
 * AI 대화방(채팅방). 서버 `/api/ai/conversations` 응답 항목과 대응.
 * 필드 유무/명칭이 응답마다 다를 수 있어 conversationId 외에는 nullable + 기본값으로 둔다.
 */
data class ConversationDto(
    val conversationId: String,
    val title: String? = null,
    val createTime: String? = null,
    val updateTime: String? = null
) {
    val displayTitle: String get() = title?.ifBlank { null } ?: "새 대화"
}
