package kr.co.fixlog.data.remote.dto

/**
 * 서버 응답 DTO. 서버의 com.fixlog.presentation.dto.response.DocumentDto와 1:1 대응.
 *
 * - blocks         : 문서 본문(BlockNote 블록 트리)의 JSON 문자열. 목록 조회 시 null일 수 있음.
 * - plainText      : 본문에서 추출한 평문(검색/미리보기용). null 가능.
 * - contentHash    : 본문 해시(변경 감지용). null 가능.
 * - createUserName : 작성자 표시명(상세/공유 응답에만 포함될 수 있음, 목록엔 없을 수 있음).
 * - labels         : 문서 라벨(태그). 서버가 목록 응답에 포함할 경우에만 채워진다(없으면 null).
 *
 * NOTE: 서버에는 workspace 개념이 없어 workspaceId 필드는 제거됨.
 *       createUserName / labels 는 응답에 따라 없을 수 있어 기본값 null 로 둔다(파싱 실패 방지).
 */
data class DocumentDto(
    val documentId: String,
    val folderId: String?,
    val title: String,
    val blocks: String?,
    val plainText: String?,
    val contentHash: String?,
    val ordinal: Int?,
    val createUser: String?,
    val createTime: String?,
    val updateUser: String?,
    val updateTime: String?,
    val createUserName: String? = null,
    val labels: List<LabelDto>? = null
)
