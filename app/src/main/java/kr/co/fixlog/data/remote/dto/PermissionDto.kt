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
    val permissionType: String?,  // ALLOW
    val canDownload: Boolean?,
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
