package kr.co.fixlog.data.remote.dto

/**
 * 사이드바/이동 대상용 폴더 트리 노드. 서버의 com.fixlog.presentation.dto.response.FolderTreeDto와 1:1 대응.
 * documentCount는 직속 문서 수(하위 폴더 문서 미포함). children는 하위 폴더.
 */
data class FolderTreeDto(
    val folderId: String,
    val parentId: String?,
    val folderName: String,
    val ordinal: Int?,
    val documentCount: Long,
    val children: List<FolderTreeDto>
)
