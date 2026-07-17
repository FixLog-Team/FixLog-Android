package kr.co.fixlog.data.remote.dto

/**
 * POST/PUT 폴더 API의 요청 바디. 서버의 com.fixlog.presentation.dto.request.FolderRequest와 1:1 대응.
 * 서버 계약: { parentId, folderName }. 루트 직속 폴더면 parentId = null.
 */
data class FolderRequest(
    val parentId: String?,
    val folderName: String
)
