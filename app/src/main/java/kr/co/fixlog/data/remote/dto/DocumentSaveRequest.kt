package kr.co.fixlog.data.remote.dto

/**
 * PUT /api/documents/{documentId} 요청 바디. 서버의 DocumentSaveRequest와 1:1 대응.
 *
 * - title : 문서 제목 (필수)
 * - blocks: BlockNote 에디터의 블록 트리. 서버에서는 JsonNode(임의 JSON)로 받는다.
 *           안드로이드에서는 WebView가 넘겨준 블록 JSON을 그대로 실어 보내야 하므로
 *           타입을 Any?로 두고 Moshi가 Map/List 형태로 직렬화하도록 한다.
 *           (DocumentApi.saveContent가 JSON 문자열을 파싱해 채워 준다)
 */
data class DocumentSaveRequest(
    val title: String,
    val blocks: Any?
)