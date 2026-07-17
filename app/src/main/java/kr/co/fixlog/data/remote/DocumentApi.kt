package kr.co.fixlog.data.remote

import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.fixlog.data.format.BlockFormatConverter
import kr.co.fixlog.data.remote.dto.ApiResponse
import kr.co.fixlog.data.remote.dto.DocumentCreateRequest
import kr.co.fixlog.data.remote.dto.DocumentDto
import kr.co.fixlog.data.remote.dto.DocumentDuplicateDto
import kr.co.fixlog.data.remote.dto.DocumentMoveRequest
import kr.co.fixlog.data.remote.dto.DocumentPageDto
import kr.co.fixlog.data.remote.dto.DocumentSaveRequest
import kr.co.fixlog.data.remote.dto.DocumentTitleRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.lang.reflect.Type

/**
 * 서버 문서(document) API 호출 모음. 모든 메서드는 suspend이며 IO Dispatcher에서 실행되고,
 * 실패(파싱/네트워크/서버 error code)는 [IOException]으로 throw한다.
 *
 * 서버 API 매핑(DocumentController, /api/documents):
 *   POST   /api/documents                           → [create]
 *   GET    /api/documents?folderId&page&size        → [list]
 *   GET    /api/documents/{documentId}              → [getDocument]
 *   PUT    /api/documents/{documentId}              → [saveContent]
 *   DELETE /api/documents/{documentId}              → [delete]
 *   POST   /api/documents/{documentId}/duplicate    → [duplicate]
 *   PATCH  /api/documents/{documentId}/title        → [updateTitle]
 *   PATCH  /api/documents/{documentId}/move         → [move]
 *   GET    /api/documents/{documentId}/save-state   → [getSaveState]
 *   GET    /api/documents/{documentId}/download     → [downloadPdf] (application/pdf 바이트)
 */
object DocumentApi {
    private const val PATH = "/api/documents"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 새 문서 생성. 루트 직속이면 folderId = null. 생성된 문서를 반환. */
    suspend fun create(folderId: String?, title: String): DocumentDto {
        val payload = ApiClient.moshi.adapter(DocumentCreateRequest::class.java)
            .toJson(DocumentCreateRequest(folderId, title))
        val request = Request.Builder()
            .url(ApiClient.BASE_URL + PATH)
            .post(payload.toRequestBody(JSON))
            .build()
        return execute(request, DocumentDto::class.java)
    }

    /**
     * 문서 목록(페이지네이션). folderId=null이면 전체(최근순).
     * 서버 정렬은 updateTime DESC.
     */
    suspend fun list(folderId: String? = null, page: Int = 0, size: Int = 20): DocumentPageDto {
        val builder = (ApiClient.BASE_URL + PATH).toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("size", size.toString())
        if (!folderId.isNullOrBlank()) builder.addQueryParameter("folderId", folderId)
        val request = Request.Builder().url(builder.build()).get().build()
        return execute(request, DocumentPageDto::class.java)
    }

    /** 문서 상세 조회(본문 blocks 포함). 서버 Editor.js blocks 를 BlockNote 로 변환해 반환. */
    suspend fun getDocument(documentId: String): DocumentDto {
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}$PATH/$documentId")
            .get()
            .build()
        val dto: DocumentDto = execute(request, DocumentDto::class.java)
        return dto.blocks?.let { dto.copy(blocks = BlockFormatConverter.editorJsToBlockNote(it)) } ?: dto
    }

    /**
     * 문서 제목 + 본문(blocks) 저장.
     * @param blocksJson BlockNote가 내보낸 블록 트리 JSON 문자열(배열 또는 객체). 그대로 서버에 전달된다.
     */
    suspend fun saveContent(documentId: String, title: String, blocksJson: String): DocumentDto {
        // 클라이언트(BlockNote) → 서버(Editor.js) 포맷으로 변환 후 전송한다.
        val editorJsJson = BlockFormatConverter.blockNoteToEditorJs(blocksJson)
        // 문자열을 실제 JSON(Map/List)으로 파싱해 담아야 서버에 "문자열"이 아닌 JSON 노드로 전송된다.
        val blocks = ApiClient.moshi.adapter(Any::class.java).fromJson(editorJsJson)
        val req = DocumentSaveRequest(title = title, blocks = blocks)
        val payload = ApiClient.moshi.adapter(DocumentSaveRequest::class.java).toJson(req)
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}$PATH/$documentId")
            .put(payload.toRequestBody(JSON))
            .build()
        return execute(request, DocumentDto::class.java)
    }

    /** 문서 삭제. */
    suspend fun delete(documentId: String) {
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}$PATH/$documentId")
            .delete()
            .build()
        executeUnit(request)
    }

    /** 문서 복제. 새로 생성된 문서의 id/폴더/제목을 반환. */
    suspend fun duplicate(documentId: String): DocumentDuplicateDto {
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}$PATH/$documentId/duplicate")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return execute(request, DocumentDuplicateDto::class.java)
    }

    /** 문서 제목만 변경(PATCH). 갱신된 문서를 반환. */
    suspend fun updateTitle(documentId: String, title: String): DocumentDto {
        val payload = ApiClient.moshi.adapter(DocumentTitleRequest::class.java)
            .toJson(DocumentTitleRequest(title))
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}$PATH/$documentId/title")
            .patch(payload.toRequestBody(JSON))
            .build()
        return execute(request, DocumentDto::class.java)
    }

    /** 문서를 다른 폴더로 이동(PATCH). 루트로 이동 시 folderId = null. */
    suspend fun move(documentId: String, folderId: String?): DocumentDto {
        val payload = ApiClient.moshi.adapter(DocumentMoveRequest::class.java)
            .toJson(DocumentMoveRequest(folderId))
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}$PATH/$documentId/move")
            .patch(payload.toRequestBody(JSON))
            .build()
        return execute(request, DocumentDto::class.java)
    }

    /** 저장 상태 조회. 결과 스키마가 명세에 없어 result를 raw JSON 문자열로 반환한다. */
    suspend fun getSaveState(documentId: String): String {
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}$PATH/$documentId/save-state")
            .get()
            .build()
        return executeRawResult(request)
    }

    /**
     * 문서 다운로드. 서버는 application/pdf 바이너리(ResponseEntity<byte[]>)로 응답한다.
     * 공통 JSON 래퍼가 아니므로 바이트 배열을 그대로 반환한다.
     */
    suspend fun downloadPdf(documentId: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}$PATH/$documentId/download")
            .get()
            .build()
        val response = ApiClient.httpClient.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            it.body?.bytes() ?: throw IOException("Empty PDF body")
        }
    }

    // --- 공통 실행부 (FolderApi와 동일 규약) ---

    private suspend fun <T> execute(request: Request, resultType: Type): T = withContext(Dispatchers.IO) {
        val response = ApiClient.httpClient.newCall(request).execute()
        val body = response.use { it.body?.string() }
            ?: throw IOException("Empty response body")

        val parameterized = Types.newParameterizedType(ApiResponse::class.java, resultType)
        @Suppress("UNCHECKED_CAST")
        val adapter = ApiClient.moshi.adapter<ApiResponse<T>>(parameterized)
        val apiResponse = adapter.fromJson(body)
            ?: throw IOException("Failed to parse response: $body")

        if (!apiResponse.isSuccess()) {
            throw IOException("API error: code=${apiResponse.code}, message=${apiResponse.message}")
        }
        apiResponse.result ?: throw IOException("API success but result was null")
    }

    private suspend fun executeUnit(request: Request) = withContext(Dispatchers.IO) {
        val response = ApiClient.httpClient.newCall(request).execute()
        val body = response.use { it.body?.string() }
        if (body.isNullOrBlank()) {
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return@withContext
        }
        val obj = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext
        val code = obj.optString("code")
        if (code.isNotEmpty() && code != "SUCCESS") {
            throw IOException("API error: code=$code, message=${obj.optString("message")}")
        }
    }

    /**
     * result 필드를 타입 무관하게 raw로 뽑아 문자열로 반환.
     * - result가 JSON 문자열이면 그 문자열 값을, 객체/배열이면 JSON 텍스트를 반환.
     * - result가 null이면 빈 문자열.
     */
    private suspend fun executeRawResult(request: Request): String = withContext(Dispatchers.IO) {
        val response = ApiClient.httpClient.newCall(request).execute()
        val body = response.use { it.body?.string() }
            ?: throw IOException("Empty response body")
        val obj = JSONObject(body)
        val code = obj.optString("code")
        if (code.isNotEmpty() && code != "SUCCESS") {
            throw IOException("API error: code=$code, message=${obj.optString("message")}")
        }
        if (obj.isNull("result")) "" else obj.get("result").toString()
    }
}
