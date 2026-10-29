package kr.co.fixlog.data.remote

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.fixlog.data.remote.dto.ApiResponse
import kr.co.fixlog.data.remote.dto.ImageUploadDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * 이미지 API(웹 imagesApi 와 1:1).
 *   POST /api/images/upload (multipart "file") → [upload]
 *   GET  /api/images/{imageId}                → 302 Presigned URL. 블록에는 업로드 결과 url 을 그대로 저장한다.
 */
object ImageApi {

    /** 서버가 허용하는 이미지 MIME 타입과 최대 크기(10MB). 업로드 전 1차 검증에 쓴다. */
    val ALLOWED_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/svg+xml")
    const val MAX_BYTES = 10 * 1024 * 1024

    /** 실제 업로드 한도: 서버 앞단 nginx 가 1MB 초과 요청을 413 으로 막는다(multipart 오버헤드 여유 포함). */
    const val UPLOAD_LIMIT = 1_000_000

    suspend fun upload(bytes: ByteArray, fileName: String, mimeType: String): ImageUploadDto = withContext(Dispatchers.IO) {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}/api/images/upload")
            .post(multipart)
            .build()
        val body = ApiClient.httpClient.newCall(request).execute().use { it.body?.string() }
            ?: throw IOException("Empty response body")
        val type = Types.newParameterizedType(ApiResponse::class.java, ImageUploadDto::class.java)
        val apiResponse = ApiClient.moshi.adapter<ApiResponse<ImageUploadDto>>(type).fromJson(body)
            ?: throw IOException("Failed to parse: $body")
        if (!apiResponse.isSuccess()) {
            throw IOException("API error: code=${apiResponse.code}, message=${apiResponse.message}")
        }
        apiResponse.result ?: throw IOException("API success but result was null")
    }

    /**
     * WebView 의 <img> 요청은 Bearer 토큰을 못 붙여 GET /api/images/{id} 가 401 이 난다.
     * 그 요청만 인증이 붙는 OkHttp 로 대신 받아(302 → Presigned URL 따라감) WebView 에 넘긴다.
     * WebViewClient.shouldInterceptRequest(백그라운드 스레드)에서 호출한다. 대상이 아니거나 실패하면 null.
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (request.method != "GET" || !url.startsWith("${ApiClient.BASE_URL}/api/images/")) return null
        return runCatching {
            val response = ApiClient.httpClient.newCall(Request.Builder().url(url).build()).execute()
            val body = response.body
            if (!response.isSuccessful || body == null) {
                response.close()
                null
            } else {
                val type = body.contentType()?.let { "${it.type}/${it.subtype}" } ?: "image/*"
                WebResourceResponse(type, null, body.byteStream())
            }
        }.getOrNull()
    }
}
