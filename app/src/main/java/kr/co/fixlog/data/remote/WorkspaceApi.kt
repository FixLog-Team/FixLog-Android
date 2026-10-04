package kr.co.fixlog.data.remote

import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.fixlog.data.remote.dto.ApiResponse
import kr.co.fixlog.data.remote.dto.WorkspaceDto
import kr.co.fixlog.data.remote.dto.WorkspaceMemberDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.lang.reflect.Type

/**
 * 워크스페이스 API 호출 모음. suspend + IO Dispatcher, 실패는 [IOException].
 * 기존 [FolderApi]/[DocumentApi]와 동일한 공통 래퍼(execute/executeUnit) 규약을 따른다.
 *
 * 서버 API 매핑(§3.7 / §4, /api/workspaces):
 *   GET    /api/workspaces              → [list]   (내가 속한 워크스페이스 목록)
 *   POST   /api/workspaces              → [create] (새 협업 워크스페이스 생성)
 *   POST   /api/workspaces/{id}/leave   → [leave]  (구성원 나가기)
 *   DELETE /api/workspaces/{id}         → [delete] (소유자 삭제)
 *
 * NOTE: 생성 요청 바디 필드명은 Swagger 실제 계약으로 검증 필요. 웹 명명(workspaceName)을 기본으로 보낸다.
 */
object WorkspaceApi {
    private const val PATH = "/api/workspaces"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 내가 속한 워크스페이스 목록. 개인 WS 포함. */
    suspend fun list(): List<WorkspaceDto> {
        val request = Request.Builder().url(ApiClient.BASE_URL + PATH).get().build()
        val listType = Types.newParameterizedType(List::class.java, WorkspaceDto::class.java)
        return execute(request, listType)
    }

    /** 워크스페이스 구성원 목록. 소유자(userId) → 이름 해석에 사용. 구성원만 조회 가능. */
    suspend fun members(workspaceId: String): List<WorkspaceMemberDto> {
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}$PATH/$workspaceId/members")
            .get()
            .build()
        val listType = Types.newParameterizedType(List::class.java, WorkspaceMemberDto::class.java)
        return execute(request, listType)
    }

    /** 새 협업 워크스페이스 생성. 생성된 워크스페이스를 반환. */
    suspend fun create(name: String): WorkspaceDto {
        val payload = JSONObject().put("workspaceName", name).toString()
        val request = Request.Builder()
            .url(ApiClient.BASE_URL + PATH)
            .post(payload.toRequestBody(JSON))
            .build()
        return execute(request, WorkspaceDto::class.java)
    }

    /** 워크스페이스 나가기(구성원). */
    suspend fun leave(workspaceId: String) {
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}$PATH/$workspaceId/leave")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        executeUnit(request)
    }

    /** 워크스페이스 삭제(소유자). */
    suspend fun delete(workspaceId: String) {
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}$PATH/$workspaceId")
            .delete()
            .build()
        executeUnit(request)
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
}
