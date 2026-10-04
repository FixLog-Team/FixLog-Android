package kr.co.fixlog.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.fixlog.data.remote.dto.TokenResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * 인증(auth) API 호출 모음. suspend + IO Dispatcher, 실패는 [IOException].
 *
 * 서버 API 매핑(auth-controller):
 *   POST /auth/token/refresh → [refresh]
 *   POST /auth/exchange      → [exchange]  (Custom Tabs 로그인의 1회용 code → 토큰 교환)
 *   GET  /auth/token         → [session]
 *
 * 주의: 두 엔드포인트 모두 Swagger에 요청/응답 스키마가 구체적으로 노출되어 있지 않다.
 *  - refresh 요청 바디는 generic object로만 정의됨 → refreshToken을 담아 보낸다고 가정.
 *  - 응답 result 스키마도 미정 → JSON에서 필요한 필드를 직접 파싱한다.
 *  서버 실제 계약이 다르면 이 파일의 요청 바디/파싱부만 맞추면 된다.
 */
object AuthApi {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * Access Token 재발급. refreshToken을 바디로 보내고, result에서 accessToken/refreshToken을 파싱한다.
     * (AuthInterceptor 주석의 "토큰 자동 갱신 미구현"을 채우기 위한 호출부)
     */
    suspend fun refresh(refreshToken: String?): TokenResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("refreshToken", refreshToken ?: JSONObject.NULL)
        }.toString()
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}/auth/token/refresh")
            .post(payload.toRequestBody(JSON))
            .build()

        val response = ApiClient.httpClient.newCall(request).execute()
        val body = response.use { it.body?.string() }
            ?: throw IOException("Empty response body")
        val obj = JSONObject(body)
        val code = obj.optString("code")
        if (code.isNotEmpty() && code != "SUCCESS") {
            throw IOException("API error: code=$code, message=${obj.optString("message")}")
        }

        // result 안에 있을 수도, 최상위에 있을 수도 있어 둘 다 탐색.
        val result = obj.optJSONObject("result") ?: obj
        val access = result.optString("accessToken").ifBlank { result.optString("token") }
        if (access.isBlank()) throw IOException("refresh succeeded but no accessToken in body: $body")
        val refresh = result.optString("refreshToken").ifBlank { null }
        TokenResponse(accessToken = access, refreshToken = refresh)
    }

    /**
     * Custom Tabs 로그인 콜백으로 받은 1회용 code를 서버 JWT(access/refresh)로 교환한다.
     * POST /auth/exchange { code }. result(또는 최상위)에서 accessToken/refreshToken을 파싱한다.
     * (딥링크에 토큰을 싣지 않기 위한 권장 방식 — OAUTH_CUSTOM_TABS_전환.md 참고)
     */
    suspend fun exchange(code: String): TokenResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("code", code).toString()
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}/auth/exchange")
            .post(payload.toRequestBody(JSON))
            .build()

        val response = ApiClient.httpClient.newCall(request).execute()
        val body = response.use { it.body?.string() }
            ?: throw IOException("Empty response body")
        val obj = JSONObject(body)
        val respCode = obj.optString("code")
        if (respCode.isNotEmpty() && respCode != "SUCCESS") {
            throw IOException("API error: code=$respCode, message=${obj.optString("message")}")
        }
        val result = obj.optJSONObject("result") ?: obj
        val access = result.optString("accessToken").ifBlank { result.optString("token") }
        if (access.isBlank()) throw IOException("exchange succeeded but no accessToken in body")
        val refresh = result.optString("refreshToken").ifBlank { null }
        TokenResponse(accessToken = access, refreshToken = refresh)
    }

    /**
     * 현재 세션/토큰 정보 조회. 응답 result 스키마가 명세에 없어 raw JSON 문자열로 반환한다.
     * 필요한 필드(사용자 id/email 등)는 호출 측에서 파싱한다.
     */
    suspend fun session(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}/auth/token")
            .get()
            .build()
        val response = ApiClient.httpClient.newCall(request).execute()
        val body = response.use { it.body?.string() }
            ?: throw IOException("Empty response body")
        val obj = JSONObject(body)
        val code = obj.optString("code")
        if (code.isNotEmpty() && code != "SUCCESS") {
            throw IOException("API error: code=$code, message=${obj.optString("message")}")
        }
        if (obj.isNull("result")) body else obj.get("result").toString()
    }
}