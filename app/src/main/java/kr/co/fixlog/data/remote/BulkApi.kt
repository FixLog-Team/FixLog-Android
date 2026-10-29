package kr.co.fixlog.data.remote

import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.fixlog.data.remote.dto.ApiResponse
import kr.co.fixlog.data.remote.dto.BulkResultDto
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 일괄 작업 API(문서+폴더 혼합). 서버 BulkController 와 1:1.
 *   DELETE /api/bulk           body { documentIds, folderIds }                 → [remove] (휴지통으로)
 *   PATCH  /api/bulk/move      body { documentIds, folderIds, targetFolderId } → [move] (null 이면 루트)
 *   GET    /api/bulk/download  ?documentIds=&folderIds= (반복 키, ZIP)          → [download]
 */
object BulkApi {
    private const val PATH = "/api/bulk"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 한 번에 다운로드할 수 있는 최대 개수(서버 검증값과 동일). */
    const val DOWNLOAD_MAX_DOCUMENTS = 100
    const val DOWNLOAD_MAX_FOLDERS = 50

    suspend fun remove(documentIds: List<String>, folderIds: List<String>): BulkResultDto {
        val request = Request.Builder()
            .url(ApiClient.BASE_URL + PATH)
            .delete(targets(documentIds, folderIds).toString().toRequestBody(JSON))
            .build()
        return execute(request)
    }

    suspend fun move(documentIds: List<String>, folderIds: List<String>, targetFolderId: String?): BulkResultDto {
        val body = targets(documentIds, folderIds).put("targetFolderId", targetFolderId ?: JSONObject.NULL)
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}$PATH/move")
            .patch(body.toString().toRequestBody(JSON))
            .build()
        return execute(request)
    }

    suspend fun download(documentIds: List<String>, folderIds: List<String>): ByteArray = withContext(Dispatchers.IO) {
        val url = "${ApiClient.BASE_URL}$PATH/download".toHttpUrl().newBuilder().apply {
            documentIds.forEach { addQueryParameter("documentIds", it) }
            folderIds.forEach { addQueryParameter("folderIds", it) }
        }.build()
        ApiClient.httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw DocumentApi.HttpStatusException(response.code, "HTTP ${response.code}")
            response.body?.bytes() ?: throw IOException("Empty response body")
        }
    }

    private fun targets(documentIds: List<String>, folderIds: List<String>) = JSONObject()
        .put("documentIds", JSONArray(documentIds))
        .put("folderIds", JSONArray(folderIds))

    private suspend fun execute(request: Request): BulkResultDto = withContext(Dispatchers.IO) {
        val body = ApiClient.httpClient.newCall(request).execute().use { it.body?.string() }
            ?: throw IOException("Empty response body")
        val type = Types.newParameterizedType(ApiResponse::class.java, BulkResultDto::class.java)
        val apiResponse = ApiClient.moshi.adapter<ApiResponse<BulkResultDto>>(type).fromJson(body)
            ?: throw IOException("Failed to parse: $body")
        if (!apiResponse.isSuccess()) {
            throw IOException("API error: code=${apiResponse.code}, message=${apiResponse.message}")
        }
        apiResponse.result ?: throw IOException("API success but result was null")
    }
}
