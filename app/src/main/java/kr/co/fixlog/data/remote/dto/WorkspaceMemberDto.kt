package kr.co.fixlog.data.remote.dto

/**
 * 워크스페이스 구성원. 서버 GET /api/workspaces/{id}/members 응답 요소.
 * 리소스 소유자(createUser=userId)를 표시용 이름으로 바꾸는 데 사용한다.
 */
data class WorkspaceMemberDto(
    val userId: String?,
    val userName: String?,
    val email: String?,
    val role: String?,
    val joinedAt: String? = null
)
