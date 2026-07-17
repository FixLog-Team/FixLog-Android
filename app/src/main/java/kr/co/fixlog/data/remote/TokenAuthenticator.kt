package kr.co.fixlog.data.remote

import android.content.Context
import android.util.Log
import kr.co.fixlog.util.SessionManager
import kr.co.fixlog.util.TokenManager
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * accessToken 만료(HTTP 401 UNAUTHORIZED) 시 refreshToken으로 재발급한 뒤 원 요청을 재시도한다.
 *
 * 동작:
 *  1) 서버가 401을 응답하면 OkHttp가 이 Authenticator를 호출한다.
 *  2) 저장된 refreshToken으로 POST /auth/token/refresh 를 동기 호출해 새 accessToken을 받는다.
 *  3) 새 토큰을 저장하고, Authorization 헤더를 교체한 요청을 반환하면 OkHttp가 자동 재시도한다.
 *
 * 토큰 유효시간(서버 기준):
 *  - accessToken : 1시간 (3,600,000ms)
 *  - refreshToken: 14일 (1,209,600,000ms)
 *
 * 실패 처리:
 *  - refreshToken이 없거나 재발급이 실패(= refreshToken도 만료/무효)하면 저장된 토큰을 지우고
 *    null을 반환한다. 그러면 401이 호출자에게 전파되고, 다음 진입 시 로그인 화면으로 유도된다.
 *  - 무한 루프 방지: 재시도 요청이 또 401이면(priorResponse 존재) 더 이상 갱신하지 않는다.
 */
class TokenAuthenticator(private val context: Context) : Authenticator {

    // /auth/token/refresh 전용 클라이언트. 메인 httpClient(인터셉터/오센티케이터 포함)를 재사용하면
    // 재귀 호출 위험이 있어, 부가 로직 없는 별도 클라이언트로 호출한다.
    private val refreshClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        // 이미 한 번 재시도(갱신)했는데 또 401이면 포기 → 401 전파.
        if (response.priorResponse != null) {
            Log.d(TAG, "재발급 후에도 401 → 재시도 중단")
            clearAndReturnNull()
            return null
        }

        val refreshToken = TokenManager.getRefreshToken(context)
        if (refreshToken.isNullOrBlank()) {
            Log.d(TAG, "refreshToken 없음 → 재발급 불가 → 로그인 화면으로 이동")
            clearAndReturnNull()
            return null
        }

        synchronized(lock) {
            // 이 요청에 실렸던 토큰. 다른 스레드가 그 사이 이미 갱신했는지 판별하는 데 쓴다.
            val usedToken = response.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
            val currentToken = TokenManager.getAccessToken(context)

            // 다른 스레드가 이미 새 토큰으로 갱신해 둔 경우: 재발급 없이 최신 토큰으로 바로 재시도.
            if (!currentToken.isNullOrBlank() && currentToken != usedToken) {
                Log.d(TAG, "다른 요청이 이미 토큰 갱신함 → 최신 토큰으로 재시도")
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }

            val newAccessToken = refreshAccessToken(refreshToken)
            if (newAccessToken == null) {
                Log.d(TAG, "토큰 재발급 실패 → 로그아웃 처리")
                clearAndReturnNull()
                return null
            }

            // 서버 refresh 응답에는 refreshToken이 없으므로 access만 갱신(기존 refresh 유지).
            TokenManager.saveTokens(context, newAccessToken)
            Log.d(TAG, "토큰 재발급 성공 → 원 요청 재시도")
            return response.request.newBuilder()
                .header("Authorization", "Bearer $newAccessToken")
                .build()
        }
    }

    /**
     * POST /auth/token/refresh 를 동기 호출해 새 accessToken을 파싱한다. 실패 시 null.
     * (Authenticator는 OkHttp의 백그라운드 스레드에서 실행되므로 blocking 호출이 허용된다.)
     */
    private fun refreshAccessToken(refreshToken: String): String? {
        val payload = JSONObject().put("refreshToken", refreshToken).toString()
        val request = Request.Builder()
            .url("${ApiClient.BASE_URL}/auth/token/refresh")
            .post(payload.toRequestBody(JSON))
            .build()

        return runCatching {
            refreshClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isBlank()) return@use null
                val obj = JSONObject(body)
                val code = obj.optString("code")
                if (code.isNotEmpty() && code != "SUCCESS") return@use null
                // accessToken은 result 안 또는 최상위 어디에 있어도 잡히도록 둘 다 탐색.
                val result = obj.optJSONObject("result") ?: obj
                result.optString("accessToken").ifBlank { result.optString("token") }
                    .ifBlank { null }
            }
        }.getOrNull()
    }

    /**
     * refreshToken까지 무효 → 세션 만료로 간주.
     * 저장된 토큰을 지우고 로그인 화면으로 즉시 이동한다(§8: 재발급 실패 시 로그인 페이지로 이동).
     */
    private fun clearAndReturnNull() {
        SessionManager.notifySessionExpired()
    }

    companion object {
        private const val TAG = "TokenAuthenticator"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
