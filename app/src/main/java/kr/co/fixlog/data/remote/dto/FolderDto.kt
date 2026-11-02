package kr.co.fixlog.data.remote.dto

/**
 * 서버 응답 DTO. 서버의 com.fixlog.presentation.dto.response.FolderDto와 1:1 대응.
 * createTime / updateTime은 ISO-8601 Instant 문자열(예: "2026-05-19T12:34:56.789Z")로 받는다.
 *
 * NOTE: 서버에는 workspace 개념이 없다(사용자 소유권 기반). 과거 workspaceId 필드는 제거됨.
 */
data class FolderDto(
    val folderId: String,
    val parentId: String?,
    val folderName: String,
    val ordinal: Int?,
    val createUser: String?,
    val createTime: String?,
    val updateUser: String?,
    val updateTime: String?,
    // 원래 부모 폴더에 접근 권한이 없어 가장 가까운 접근 가능 조상 목록에 끌어올려진 항목이면 true.
    val orphaned: Boolean = false,
    val originalParentId: String? = null
)
