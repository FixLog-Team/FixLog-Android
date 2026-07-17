package kr.co.fixlog.data.remote.dto

/**
 * PATCH /api/documents/{documentId}/title 요청 바디. 서버의 DocumentTitleRequest와 1:1 대응.
 */
data class DocumentTitleRequest(
    val title: String
)
