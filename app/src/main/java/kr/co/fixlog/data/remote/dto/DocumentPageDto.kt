package kr.co.fixlog.data.remote.dto

/**
 * GET /api/documents 페이지네이션 응답. 서버의 com.fixlog.presentation.dto.response.DocumentPageDto와 1:1 대응.
 */
data class DocumentPageDto(
    val items: List<DocumentDto>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean
)
