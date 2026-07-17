package kr.co.fixlog.data.remote

import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.fixlog.data.remote.dto.ApiResponse
import kr.co.fixlog.data.remote.dto.FolderContentsDto
import kr.co.fixlog.data.remote.dto.FolderDto
import kr.co.fixlog.data.remote.dto.FolderMoveRequest
import kr.co.fixlog.data.remote.dto.FolderRequest
import kr.co.fixlog.data.remote.dto.FolderTreeDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.lang.reflect.Type

/**
 * 서버 폴더 CRUD API 호출 모음. 모든 메서드는 suspend로 IO Dispatcher에서 실행된다.
 * 호출 실패(파싱 실패, 네트워크 오류, 서버 error code)는 모두 [IOException]으로 throw.
 *
 * 서버 API 매핑(FolderController, /api/folders):
 *   POST   /api/folders                        → [createFolder]
 *   GET    /api/folders                        → [getRootContents]
 *   GET    /api/folders/tree                   → [getFolderTree]
 *   GET    /api/folders/{folderId}             → [getFolder]
 *   GET    /api/folders/{folderId}/contents    → [getFolderContents]
 *   PUT    /api/folders/{folderId}             → [updateFolder]
 *   PATCH  /api/folders/{folderId}/move        → [moveFolder]
 *   DELETE /api/folders/{folderId}             → [deleteFolder]
 *
 * NOTE: 서버는 인증된 사용자 기준으로 소유 폴더를 판단한다. 과거의 workspaceId 파라미터는 없다.
 */
object FolderApi {
    private const val PATH = "/api/folders"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun createFolder(req: FolderRequest): FolderDto {
        val payload = ApiClient.moshi.adapter(FolderRequest::class.java).toJson(req)
        val request = Request.Builder()
            .url(ApiClient.BASE_URL + PATH)
            .post(payload.toRequestBody(JSON))
            .build()
        return execute(request, FolderDto::class.java)
    }

    /** 루트(My Documents) 직속 하위 폴더 + 문서 조회. */
    suspend fun getRootContents(): FolderContentsDto {
        val request = Request.Builder().url(ApiClient.BASE_URL + PATH).get().build()
        return execute(request, FolderContentsDto::class.java)
    }

    /** 특정 폴더의 하위 폴더 + 문서 조회. */
    suspend fun getFolderContents(folderId: String): FolderContentsDto {
        val request = Request.Builder()
            .url(ApiClient.BASE_URL + "$PATH/$folderId/contents")
            .get()
            .build()
        return execute(request, FolderContentsDto::class.java)
    }

    /** 폴더 단건 조회. */
    suspend fun getFolder(folderId: String): FolderDto {
        val request = Request.Builder()
            .url(ApiClient.BASE_URL + "$PATH/$folderId")
            .get()
            .build()
        return execute(request, FolderDto::class.java)
    }

    /** 전체 폴더 트리(사이드바/이동 대상 선택용). 중첩 노드 목록. */
    suspend fun getFolderTree(): List<FolderTreeDto> {
        val request = Request.Builder()
            .url(ApiClient.BASE_URL + "$PATH/tree")
            .get()
            .build()
        val listType = Types.newParameterizedType(List::class.java, FolderTreeDto::class.java)
        return execute(request, listType)
    }

    suspend fun updateFolder(folderId: String, req: FolderRequest): FolderDto {
        val payload = ApiClient.moshi.adapter(FolderRequest::class.java).toJson(req)
        val request = Request.Builder()
            .url(ApiClient.BASE_URL + "$PATH/$folderId")
            .put(payload.toRequestBody(JSON))
            .build()
        return execute(request, FolderDto::class.java)
    }

    /** 폴더 이동(PATCH). 루트로 이동 시 parentId = null. */
    suspend fun moveFolder(folderId: String, parentId: String?): FolderDto {
        val payload = ApiClient.moshi.adapter(FolderMoveRequest::class.java)
            .toJson(FolderMoveRequest(parentId))
        val request = Request.Builder()
            .url(ApiClient.BASE_URL + "$PATH/$folderId/move")
            .patch(payload.toRequestBody(JSON))
            .build()
        return execute(request, FolderDto::class.java)
    }

    suspend fun deleteFolder(folderId: String) {
        val request = Request.Builder()
            .url(ApiClient.BASE_URL + "$PATH/$folderId")
            .delete()
            .build()
        executeUnit(request)
    }

    /**
     * 응답을 ApiResponse<T>로 파싱 후 result를 반환. 실패 시 IOException.
     * resultType은 호출 측에서 Class<T> 또는 Types.newParameterizedType(...)으로 전달.
     */
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

    /** result를 무시하고 성공 여부만 확인. DELETE처럼 본문이 단순 Response인 경우 사용. */
    private suspend fun executeUnit(request: Request) = withContext(Dispatchers.IO) {
        val response = ApiClient.httpClient.newCall(request).execute()
        val body = response.use { it.body?.string() }
        if (body.isNullOrBlank()) {
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return@withContext
        }
        val parameterized = Types.newParameterizedType(ApiResponse::class.java, Any::class.java)
        val adapter = ApiClient.moshi.adapter<ApiResponse<Any>>(parameterized)
        val apiResponse = adapter.fromJson(body)
        if (apiResponse != null && !apiResponse.isSuccess()) {
            throw IOException("API error: code=${apiResponse.code}, message=${apiResponse.message}")
        }
    }
}
