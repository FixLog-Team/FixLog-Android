package kr.co.fixlog.data.remote

import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.fixlog.data.remote.dto.ApiResponse
import kr.co.fixlog.data.remote.dto.TitleSearchResultDto
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

/**
 * 제목 검색 API.
 *   GET /search/title?q=  → [searchTitle] (폴더명 + 문서 제목, 워크스페이스 전체)
 * 경로에 /api 접두사가 없다(서버 SearchController @RequestMapping("/search")).
 */
object SearchApi {

    suspend fun searchTitle(q: String): TitleSearchResultDto = withContext(Dispatchers.IO) {
        val url = "${ApiClient.BASE_URL}/search/title".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .build()
        val request = Request.Builder().url(url).get().build()
        val body = ApiClient.httpClient.newCall(request).execute().use { it.body?.string() }
            ?: throw IOException("Empty response body")
        val type = Types.newParameterizedType(ApiResponse::class.java, TitleSearchResultDto::class.java)
        val apiResponse = ApiClient.moshi.adapter<ApiResponse<TitleSearchResultDto>>(type).fromJson(body)
            ?: throw IOException("Failed to parse: $body")
        if (!apiResponse.isSuccess()) {
            throw IOException("API error: code=${apiResponse.code}, message=${apiResponse.message}")
        }
        apiResponse.result ?: throw IOException("API success but result was null")
    }
}
