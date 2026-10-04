package kr.co.fixlog.data.remote

import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.fixlog.data.remote.dto.ApiResponse
import kr.co.fixlog.data.remote.dto.TrashItemDto
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.lang.reflect.Type

/**
 * 휴지통 API (FRONTEND_API_GUIDE 11장).
 *   GET    /api/trash                               → [list]
 *   POST   /api/trash/{resourceType}/{id}/restore   → [restore] (부모가 휴지통이면 루트로 복원)
 *   DELETE /api/trash/{resourceType}/{id}           → [purge]   (영구 삭제, 복구 불가)
 *
 * resourceType: "FOLDER" | "DOCUMENT".
 */
object TrashApi {
    private const val PATH = "/api/trash"

    suspend fun list(): List<TrashItemDto> {
        val request = Request.Builder().url(ApiClient.BASE_URL + PATH).get().build()
        val listType = Types.newParameterizedType(List::class.java, TrashItemDto::class.java)
        return execute(request, listType)
    }

    suspend fun restore(resourceType: String, resourceId: String) {
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}$PATH/$resourceType/$resourceId/restore")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        executeUnit(request)
    }

    suspend fun purge(resourceType: String, resourceId: String) {
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}$PATH/$resourceType/$resourceId")
            .delete()
            .build()
        executeUnit(request)
    }

    private suspend fun <T> execute(request: Request, resultType: Type): T = withContext(Dispatchers.IO) {
        val response = ApiClient.httpClient.newCall(request).execute()
        val body = response.use { it.body?.string() } ?: throw IOException("Empty response body")
        val parameterized = Types.newParameterizedType(ApiResponse::class.java, resultType)
        @Suppress("UNCHECKED_CAST")
        val adapter = ApiClient.moshi.adapter<ApiResponse<T>>(parameterized)
        val apiResponse = adapter.fromJson(body) ?: throw IOException("Failed to parse: $body")
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
