package kr.co.fixlog.data.remote.dto

/**
 * POST /api/documents 요청 바디. 서버의 com.fixlog.presentation.dto.request.DocumentCreateRequest와 1:1 대응.
 * 루트 직속 문서면 folderId = null.
 */
data class DocumentCreateRequest(
    val folderId: String?,
    val title: String
)
