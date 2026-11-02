package kr.co.fixlog.data.remote.dto

/**
 * 공유(권한) 대상 항목. 서버 GET /api/{documents|folders}/{id}/permissions 응답 요소.
 * 권한 모델: permissionType=ALLOW + canDownload (DENY 폐기).
 */
data class PermissionDto(
    val permissionId: String,
    val principalType: String?,   // USER | GROUP
    val principalId: String?,
    val principalName: String?,
    val principalEmail: String? = null, // USER 일 때만 값이 있다(동명이인 구별용)
    val permissionType: String?,  // ALLOW
    val canDownload: Boolean?,
    // null 이면 직접 부여. 값이 있으면 그 폴더에서 상속된 권한이라 여기서는 회수할 수 없다.
    val inheritedFromFolderId: String? = null,
    val createAt: String?
)

/**
 * 내 유효 권한. GET /api/{documents|folders}/{id}/my-permission.
 * access=false면 접근 불가. 공유 관리(부여/회수) 노출 판단 등에 사용.
 */
data class MyPermissionDto(
    val access: Boolean?,
    val canDownload: Boolean?,
    val source: String?,       // DIRECT | INHERITED | WORKSPACE_DEFAULT
    val sourceDetail: String?
)

/**
 * shared-with-me / shared-by-me 응답. { folders, documents } 형태.
 */
data class SharedResourcesDto(
    val folders: List<FolderDto> = emptyList(),
    val documents: List<DocumentDto> = emptyList()
)
