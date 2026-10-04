package kr.co.fixlog.data.remote.dto

/**
 * 휴지통 항목. 서버 GET /api/trash 응답 요소(폴더·문서 혼합, 최근 삭제순).
 * 워크스페이스 스코프이며 지운 본인·관리자만 조회/복원/영구삭제 가능.
 */
data class TrashItemDto(
    val resourceType: String,   // FOLDER | DOCUMENT
    val resourceId: String,
    val name: String?,
    val deletedBy: String?,
    val deletedAt: String?
)
