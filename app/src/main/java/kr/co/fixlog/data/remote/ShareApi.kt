package kr.co.fixlog.data.remote

import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.fixlog.data.remote.dto.ApiResponse
import kr.co.fixlog.data.remote.dto.MyPermissionDto
import kr.co.fixlog.data.remote.dto.PermissionDto
import kr.co.fixlog.data.remote.dto.SharedResourcesDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.lang.reflect.Type

/**
 * 공유(권한) API. 문서·폴더 공통 규칙(FRONTEND_API_GUIDE 9장).
 *   GET    /api/{documents|folders}/{id}/my-permission        → [myPermission]
 *   GET    /api/{documents|folders}/{id}/permissions          → [listPermissions]
 *   POST   /api/{documents|folders}/{id}/permissions          → [share] (소유자·Admin, upsert)
 *   DELETE /api/{documents|folders}/{id}/permissions/{permId} → [revoke]
 *   GET    /api/documents/shared-with-me                      → [sharedWithMe]
 *   GET    /api/documents/shared-by-me                        → [sharedByMe]
 *
 * kind: "document" | "folder".
 */
object ShareApi {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun base(kind: String): String =
        if (kind == "folder") "${ApiClient.BASE_URL}/api/folders" else "${ApiClient.BASE_URL}/api/documents"

    suspend fun myPermission(kind: String, id: String): MyPermissionDto {
        val request = Request.Builder().url("${base(kind)}/$id/my-permission").get().build()
        return execute(request, MyPermissionDto::class.java)
    }

    suspend fun listPermissions(kind: String, id: String): List<PermissionDto> {
        val request = Request.Builder().url("${base(kind)}/$id/permissions").get().build()
        val listType = Types.newParameterizedType(List::class.java, PermissionDto::class.java)
        return execute(request, listType)
    }

    /** 공유(권한 부여). 같은 대상 재전송 시 서버가 덮어쓴다(upsert). */
    suspend fun share(kind: String, id: String, email: String, canDownload: Boolean) {
        val payload = JSONObject()
            .put("email", email)
            .put("permissionType", "ALLOW")
            .put("canDownload", canDownload)
            .toString()
        val request = Request.Builder()
            .url("${base(kind)}/$id/permissions")
            .post(payload.toRequestBody(JSON))
            .build()
        executeUnit(request)
    }

    suspend fun revoke(kind: String, id: String, permissionId: String) {
        val request = Request.Builder()
            .url("${base(kind)}/$id/permissions/$permissionId")
            .delete()
            .build()
        executeUnit(request)
    }

    suspend fun sharedWithMe(): SharedResourcesDto {
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}/api/documents/shared-with-me").get().build()
        return runCatching { execute<SharedResourcesDto>(request, SharedResourcesDto::class.java) }
            .getOrDefault(SharedResourcesDto())
    }

    suspend fun sharedByMe(): SharedResourcesDto {
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}/api/documents/shared-by-me").get().build()
        return runCatching { execute<SharedResourcesDto>(request, SharedResourcesDto::class.java) }
            .getOrDefault(SharedResourcesDto())
    }

    // --- 공통 실행부 ---

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
