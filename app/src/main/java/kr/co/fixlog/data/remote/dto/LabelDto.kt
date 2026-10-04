package kr.co.fixlog.data.remote.dto

/**
 * 문서 라벨(태그). 서버 `GET /api/documents/{id}/labels` 및 목록 응답에 포함될 수 있다.
 * 필드 유무가 응답마다 다를 수 있어 모두 nullable로 둔다.
 */
data class LabelDto(
    val labelId: String? = null,
    val labelName: String? = null
)
