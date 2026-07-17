package kr.co.fixlog.data.remote.dto

/**
 * PATCH /api/folders/{folderId}/move 요청 바디. 서버의 FolderMoveRequest와 1:1 대응.
 * 루트로 이동 시 parentId = null.
 */
data class FolderMoveRequest(
    val parentId: String?
)
