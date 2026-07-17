package kr.co.fixlog.data.remote.dto

/**
 * PATCH /api/documents/{documentId}/move 요청 바디. 서버의 DocumentMoveRequest와 1:1 대응.
 * 루트로 이동(폴더에서 꺼내기)하는 경우 folderId = null.
 */
data class DocumentMoveRequest(
    val folderId: String?
)
