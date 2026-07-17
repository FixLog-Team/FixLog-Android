package kr.co.fixlog.data.remote.dto

/**
 * 의미 기반 검색/질의응답의 참고 문서 항목. 서버 com.fixlog.presentation.dto.response.SearchResultDto와 1:1 대응.
 *
 * - excerpt: 질문과 관련된 문장 발췌.
 * - score  : 벡터 유사도 점수(높을수록 관련). null 가능.
 */
data class SearchResultDto(
    val documentId: String,
    val title: String?,
    val folderId: String?,
    val excerpt: String?,
    val score: Double?
)
