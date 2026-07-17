package kr.co.fixlog.data.remote.dto

/**
 * POST /api/documents/{documentId}/duplicate 응답. 서버의 DocumentDuplicateDto와 1:1 대응.
 * 새로 생성된 문서의 id/폴더/제목만 담는다.
 */
data class DocumentDuplicateDto(
    val newDocumentId: String,
    val folderId: String?,
    val title: String
)
