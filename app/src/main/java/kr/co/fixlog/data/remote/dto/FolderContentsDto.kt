package kr.co.fixlog.data.remote.dto

/**
 * 폴더 콘텐츠 응답. 하위 폴더 목록 + 문서 목록을 함께 담는다.
 * 서버: GET /api/folders?workspaceId=X, GET /api/folders/{folderId}/contents?workspaceId=X
 */
data class FolderContentsDto(
    val folders: List<FolderDto>,
    val documents: List<DocumentDto>
)
