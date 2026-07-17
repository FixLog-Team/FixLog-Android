package kr.co.fixlog.data.remote

import android.content.Context
import kr.co.fixlog.util.TokenManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 모든 API 요청에 Authorization: Bearer {accessToken} 헤더를 자동 부착한다.
 *
 * - 저장된 토큰이 없으면 헤더 생략 (서버가 401로 응답하면 호출 측에서 재로그인 유도)
 * - 폴더 API는 현재 서버 SecurityConfig에서 인증 우회 중이지만, 향후 인증 복원 시 자동 대응하도록 항상 부착
 * - 401(accessToken 만료) 시 자동 재발급/재시도는 [TokenAuthenticator]가 담당한다.
 */
class AuthInterceptor(private val context: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = TokenManager.getAccessToken(context)
        val request = if (!token.isNullOrBlank()) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }
        return chain.proceed(request)
    }
}
