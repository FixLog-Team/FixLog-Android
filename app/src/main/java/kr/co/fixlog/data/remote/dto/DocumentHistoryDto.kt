package kr.co.fixlog.data.remote.dto

/**
 * 문서 버전 기록(히스토리). 서버 DocumentHistoryDto / DocumentHistoryPageDto / DocumentHistoryDetailDto와 1:1 대응.
 *
 * - source     : "MANUAL"(저장 시 이전 내용 보관) | "RESTORE"(복원 직전 내용 보관)
 * - createUser : 해당 버전을 만든 사용자 id(UUID). 표시명은 OwnerNames로 해석한다.
 * - createTime : ISO-8601(UTC, 예: 2026-05-19T03:34:56.789Z)
 * 목록은 최신순이며, 서버 보관 개수(기본 50) 초과분은 삭제된다.
 */
data class DocumentHistoryDto(
    val historyId: String,
    val title: String?,
    val source: String?,
    val createUser: String?,
    val createTime: String?
)

data class DocumentHistoryPageDto(
    val items: List<DocumentHistoryDto> = emptyList(),
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
    val hasNext: Boolean = false
)

/** 버전 상세: 본문(blocks)은 서버 포맷(Editor.js) JSON 문자열 — 클라이언트에서 BlockNote로 변환해 쓴다. */
data class DocumentHistoryDetailDto(
    val historyId: String,
    val documentId: String?,
    val title: String?,
    val blocks: String?,
    val contentHash: String?,
    val source: String?,
    val createUser: String?,
    val createTime: String?
)
