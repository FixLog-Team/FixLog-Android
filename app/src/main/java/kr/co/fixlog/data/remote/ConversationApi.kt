package kr.co.fixlog.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.fixlog.data.remote.dto.ConversationDto
import kr.co.fixlog.data.remote.dto.MessageDto
import kr.co.fixlog.data.remote.dto.SearchResultDto
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * AI 대화방(채팅방) API. 멀티턴 대화를 위해 stateless `/ai/ask` 대신 대화방 기반 API를 사용한다.
 *
 * 서버 API 매핑(§3.5 / §4, /api/ai/conversations):
 *   GET    /api/ai/conversations?page&size            → [list]         (대화방 목록)
 *   POST   /api/ai/conversations { title }            → [create]       (대화방 생성)
 *   GET    /api/ai/conversations/{id}/messages        → [messages]     (이력)
 *   POST   /api/ai/conversations/{id}/messages {content} → [sendMessage] (질문 전송 → 답변)
 *   DELETE /api/ai/conversations/{id}                 → [delete]       (대화방 삭제)
 *
 * NOTE(§1.3): 대화방 응답 래퍼/필드는 문서와 다를 수 있어(공통 {code,result} 기준),
 *  result가 배열 또는 {items|messages|content:[...]} 객체 어느 쪽이든 처리하도록 유연 파싱한다.
 *  전송 실패는 사유별 [AiException]으로 변환한다(요약/검색과 동일 UX).
 */
object ConversationApi {
    private const val PATH = "/api/ai/conversations"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 대화방 목록(최신순 가정). */
    suspend fun list(page: Int = 0, size: Int = 30): List<ConversationDto> = withContext(Dispatchers.IO) {
        val url = (ApiClient.BASE_URL + PATH).toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("size", size.toString())
            .build()
        val request = Request.Builder().url(url).get().build()
        val result = resultNode(runCall(request, ApiClient.httpClient))
        parseArray(result).mapNotNull { toConversation(it) }
    }

    /** 대화방 생성. */
    suspend fun create(title: String): ConversationDto = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("title", title).toString()
        val request = Request.Builder()
            .url(ApiClient.BASE_URL + PATH)
            .post(payload.toRequestBody(JSON))
            .build()
        val result = resultNode(runCall(request, ApiClient.httpClient))
        val obj = (result as? JSONObject) ?: throw IOException("Unexpected create response")
        toConversation(obj) ?: throw IOException("conversationId missing in create response")
    }

    /** 대화방 메시지 이력(오래된→최신 순서로 정렬해 반환). */
    suspend fun messages(conversationId: String, size: Int = 50): List<MessageDto> = withContext(Dispatchers.IO) {
        val url = ("${ApiClient.BASE_URL}$PATH/$conversationId/messages").toHttpUrl().newBuilder()
            .addQueryParameter("size", size.toString())
            .build()
        val request = Request.Builder().url(url).get().build()
        val result = resultNode(runCall(request, ApiClient.httpClient))
        parseArray(result).mapNotNull { toMessage(it) }.sortedBy { it.sequence ?: Long.MAX_VALUE }
    }

    /** 질문 전송 → 어시스턴트 답변 메시지(근거 문서 포함). AI 지연 대비 긴 타임아웃 클라이언트 사용. */
    suspend fun sendMessage(conversationId: String, content: String): MessageDto = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("content", content).toString()
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}$PATH/$conversationId/messages")
            .post(payload.toRequestBody(JSON))
            .build()
        val result = resultNode(runCall(request, ApiClient.aiHttpClient))
        extractAssistantMessage(result) ?: throw AiException.Empty()
    }

    /** 대화방 삭제. */
    suspend fun delete(conversationId: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}$PATH/$conversationId")
            .delete()
            .build()
        val body = runCall(request, ApiClient.httpClient)
        // 성공 코드 검증은 resultNode 내에서 수행하지만, 본문이 비어도 성공일 수 있어 code만 확인.
        if (body.isNotBlank()) {
            val obj = runCatching { JSONObject(body) }.getOrNull()
            val code = obj?.optString("code").orEmpty()
            if (code.isNotEmpty() && code != "SUCCESS") {
                throw businessCodeToAiException(code, obj?.optString("message"))
            }
        }
    }

    // --- 실행/파싱 공통부 ---

    /** 요청 실행 후 2xx면 body 반환, 아니면 사유별 [AiException]. */
    private fun runCall(request: Request, client: okhttp3.OkHttpClient): String {
        val response = try {
            client.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            throw AiException.Timeout(e)
        } catch (e: IOException) {
            throw AiException.Network(e)
        }
        val code = response.code
        val successful = response.isSuccessful
        val body = response.use { it.body?.string() }.orEmpty()
        if (!successful) throw httpErrorToAiException(code, body)
        return body
    }

    /** 공통 래퍼에서 result 노드를 꺼낸다. code!=SUCCESS면 예외. */
    private fun resultNode(body: String): Any? {
        val obj = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw AiException.Server(null, null)
        }
        val code = obj.optString("code")
        if (code.isNotEmpty() && code != "SUCCESS") {
            throw businessCodeToAiException(code, obj.optString("message"))
        }
        if (obj.isNull("result")) return null
        return obj.get("result")
    }

    /** result가 배열이거나 {items|messages|content|conversations:[...]} 객체여도 배열로 정규화. */
    private fun parseArray(result: Any?): List<JSONObject> {
        val arr: JSONArray? = when (result) {
            is JSONArray -> result
            is JSONObject -> firstArray(result, "items", "messages", "content", "conversations", "list")
            else -> null
        }
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun firstArray(obj: JSONObject, vararg keys: String): JSONArray? {
        for (k in keys) obj.optJSONArray(k)?.let { return it }
        return null
    }

    private fun toConversation(o: JSONObject): ConversationDto? {
        val id = o.optString("conversationId").ifBlank { o.optString("id") }
        if (id.isBlank()) return null
        return ConversationDto(
            conversationId = id,
            title = o.optString("title").ifBlank { null },
            createTime = o.optString("createTime").ifBlank { null },
            updateTime = o.optString("updateTime").ifBlank { null }
        )
    }

    private fun toMessage(o: JSONObject): MessageDto? {
        val content = o.optString("content").ifBlank { o.optString("answer").ifBlank { null } }
        val role = o.optString("role").ifBlank { null }
        if (content == null && role == null) return null
        return MessageDto(
            messageId = o.optString("messageId").ifBlank { o.optString("id").ifBlank { null } },
            role = role,
            content = content,
            status = o.optString("status").ifBlank { null },
            sequence = if (o.has("sequence")) o.optLong("sequence") else null,
            references = parseReferences(o)
        )
    }

    /** 전송 응답에서 어시스턴트 메시지를 뽑는다. result가 단일 메시지/객체/배열 어느 형태든 대응. */
    private fun extractAssistantMessage(result: Any?): MessageDto? {
        when (result) {
            is JSONArray -> {
                val msgs = (0 until result.length()).mapNotNull { result.optJSONObject(it) }
                    .mapNotNull { toMessage(it) }
                return msgs.lastOrNull { !it.isUser } ?: msgs.lastOrNull()
            }
            is JSONObject -> {
                // {assistantMessage:{...}} 또는 {message:{...}} 형태 우선 처리.
                result.optJSONObject("assistantMessage")?.let { return toMessage(it) }
                result.optJSONObject("message")?.let { return toMessage(it) }
                // {messages:[...]} 형태.
                val arr = firstArray(result, "messages", "items", "content")
                if (arr != null) {
                    val msgs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                        .mapNotNull { toMessage(it) }
                    return msgs.lastOrNull { !it.isUser } ?: msgs.lastOrNull()
                }
                // 단일 메시지 객체.
                return toMessage(result)
            }
            else -> return null
        }
    }

    private fun parseReferences(o: JSONObject): List<SearchResultDto> {
        val arr = firstArray(o, "references", "sources", "documents") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { r ->
            val docId = r.optString("documentId").ifBlank { r.optString("id") }
            if (docId.isBlank()) return@mapNotNull null
            SearchResultDto(
                documentId = docId,
                title = r.optString("title").ifBlank { null },
                folderId = r.optString("folderId").ifBlank { null },
                excerpt = r.optString("excerpt").ifBlank { null },
                score = if (r.has("score")) r.optDouble("score") else null
            )
        }
    }

    private fun httpErrorToAiException(httpCode: Int, body: String): AiException {
        val (code, message) = parseCodeMessage(body)
        return when (httpCode) {
            401 -> AiException.Unauthorized()
            404 -> AiException.NotFound()
            400 -> AiException.InvalidRequest(message)
            else -> AiException.Server(code, message)
        }
    }

    private fun businessCodeToAiException(code: String?, message: String?): AiException =
        when (code) {
            "UNAUTHORIZED" -> AiException.Unauthorized()
            "NOT_FOUND" -> AiException.NotFound()
            "INVALID_REQUEST" -> AiException.InvalidRequest(message)
            else -> AiException.Server(code, message)
        }

    private fun parseCodeMessage(body: String): Pair<String?, String?> =
        try {
            val obj = JSONObject(body)
            obj.optString("code").ifEmpty { null } to obj.optString("message").ifEmpty { null }
        } catch (e: Exception) {
            null to null
        }
}
