package kr.co.fixlog.data.remote

import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.fixlog.data.remote.dto.ApiResponse
import kr.co.fixlog.data.remote.dto.AskRequest
import kr.co.fixlog.data.remote.dto.AskResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * AI 기능 API 호출 모음. suspend + IO Dispatcher.
 * 실패는 사유별 [AiException](Timeout/Network/Unauthorized/NotFound/InvalidRequest/Server/Empty)으로 던진다.
 *
 * 서버 API 매핑(ai-controller, /ai):
 *   POST /ai/documents/{documentId}/summarize → [summarizeDocument]
 *   POST /ai/summarize                        → [summarize]
 *   POST /ai/ask                              → [ask]
 */
object AiApi {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

    /**
     * 문서 ID 기반 요약. 서버가 DB에서 문서 원문(plainText)을 조회해 요약한다.
     * 본인 소유 + 삭제되지 않은(usable=1) 문서만 대상이며 요청 body는 없다.
     * 요약 문자열을 반환.
     */
    suspend fun summarizeDocument(documentId: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}/ai/documents/$documentId/summarize")
            .post(EMPTY_BODY)
            .build()
        parseStringResult(runAiCall(request))
    }

    /** 원문 텍스트 요약. content = 요약할 텍스트. 요약 문자열을 반환. */
    suspend fun summarize(content: String): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("content", content).toString()
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}/ai/summarize")
            .post(payload.toRequestBody(JSON))
            .build()
        parseStringResult(runAiCall(request))
    }

    /**
     * 의미 기반 질의응답. 서버가 질문을 임베딩해 본인 소유 문서 상위 topK를 찾아 그 내용을 근거로 답변한다.
     * 답변 + 참고 문서 목록([AskResponse])을 반환. topK가 null이면 서버 기본값(5)을 쓴다.
     */
    suspend fun ask(question: String, topK: Int? = null): AskResponse = withContext(Dispatchers.IO) {
        val payload = ApiClient.moshi.adapter(AskRequest::class.java)
            .toJson(AskRequest(question, topK))
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}/ai/ask")
            .post(payload.toRequestBody(JSON))
            .build()
        parseAskResult(runAiCall(request))
    }

    /**
     * AI 요청을 실행하고 2xx면 원문 body를 반환한다.
     * 전송 오류/비정상 HTTP 상태는 사유별 [AiException]으로 변환해 던진다.
     */
    private fun runAiCall(request: Request): String {
        val response = try {
            ApiClient.aiHttpClient.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            throw AiException.Timeout(e)
        } catch (e: IOException) {
            throw AiException.Network(e)
        }
        val httpCode = response.code
        val successful = response.isSuccessful
        val body = response.use { it.body?.string() }.orEmpty()
        if (!successful) throw httpErrorToAiException(httpCode, body)
        return body
    }

    /** `{code,message,result}` 래퍼에서 result(요약 등 문자열)를 꺼낸다. 비었으면 [AiException.Empty]. */
    private fun parseStringResult(body: String): String {
        val obj = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw AiException.Server(null, null)
        }
        val code = obj.optString("code")
        if (code.isNotEmpty() && code != "SUCCESS") {
            throw businessCodeToAiException(code, obj.optString("message"))
        }
        val result = if (obj.isNull("result")) "" else obj.optString("result")
        if (result.isBlank()) throw AiException.Empty()
        return result
    }

    /** `{code,message,result}` 래퍼에서 [AskResponse]를 꺼낸다. */
    private fun parseAskResult(body: String): AskResponse {
        val type = Types.newParameterizedType(ApiResponse::class.java, AskResponse::class.java)
        val adapter = ApiClient.moshi.adapter<ApiResponse<AskResponse>>(type)
        val apiResponse = try {
            adapter.fromJson(body)
        } catch (e: Exception) {
            throw AiException.Server(null, null)
        } ?: throw AiException.Server(null, null)
        if (!apiResponse.isSuccess()) {
            throw businessCodeToAiException(apiResponse.code, apiResponse.message)
        }
        return apiResponse.result ?: throw AiException.Empty()
    }

    /** 비정상 HTTP 상태 + 응답 body를 사유별 예외로 변환한다. */
    private fun httpErrorToAiException(httpCode: Int, body: String): AiException {
        val (code, message) = parseCodeMessage(body)
        return when (httpCode) {
            401 -> AiException.Unauthorized()
            404 -> AiException.NotFound()
            400 -> AiException.InvalidRequest(message)
            else -> AiException.Server(code, message)
        }
    }

    /** 서버 공통 코드 문자열을 사유별 예외로 변환한다(2xx인데 code!=SUCCESS인 방어적 경로). */
    private fun businessCodeToAiException(code: String?, message: String?): AiException =
        when (code) {
            "UNAUTHORIZED" -> AiException.Unauthorized()
            "NOT_FOUND" -> AiException.NotFound()
            "INVALID_REQUEST" -> AiException.InvalidRequest(message)
            else -> AiException.Server(code, message)
        }

    /** 응답 body에서 code/message를 최대한 추출(실패해도 예외를 던지지 않음). */
    private fun parseCodeMessage(body: String): Pair<String?, String?> =
        try {
            val obj = JSONObject(body)
            obj.optString("code").ifEmpty { null } to obj.optString("message").ifEmpty { null }
        } catch (e: JSONException) {
            null to null
        }
}