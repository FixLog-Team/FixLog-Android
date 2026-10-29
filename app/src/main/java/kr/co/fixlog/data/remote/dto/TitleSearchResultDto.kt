package kr.co.fixlog.data.remote.dto

/**
 * GET /search/title 응답 result. 폴더 이름 + 문서 제목 통합 검색 결과(본문은 검색하지 않음).
 * 권한 필터는 서버가 적용하므로 접근 불가 항목은 포함되지 않는다.
 */
data class TitleSearchResultDto(
    val folders: List<FolderDto>,
    val documents: List<DocumentDto>
)
