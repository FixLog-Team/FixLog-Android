package kr.co.fixlog.data.remote.dto

/**
 * 워크스페이스 응답 DTO. 서버 `GET /api/workspaces` 응답 항목과 대응.
 *
 * - workspaceId : 워크스페이스 식별자. X-Workspace-Id 헤더로 사용.
 * - personal    : 개인 워크스페이스 여부. 개인 WS는 나가기/삭제 불가.
 * - role        : 현재 사용자 역할(OWNER/ADMIN/MEMBER). OWNER만 삭제, 그 외는 나가기.
 * - baseAccess  : 명시 권한 없는 구성원의 기본 접근(ALLOW/DENY).
 *
 * NOTE: 서버 실제 필드명이 다를 수 있어(Swagger 검증 필요) 모두 nullable로 두고,
 *       personal은 서버가 `personal` 또는 `isPersonal`로 내려줄 수 있어 안전하게 nullable Boolean.
 */
data class WorkspaceDto(
    val workspaceId: String,
    val workspaceName: String?,
    val personal: Boolean?,
    val role: String?,
    val baseAccess: String?,
    val createAt: String?
) {
    val displayName: String get() = workspaceName?.ifBlank { null } ?: "이름 없는 워크스페이스"
    val isPersonal: Boolean get() = personal == true
    val isOwner: Boolean get() = role.equals("OWNER", ignoreCase = true)
}
