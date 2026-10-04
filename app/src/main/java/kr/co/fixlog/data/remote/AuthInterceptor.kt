package kr.co.fixlog.data.remote

import android.content.Context
import kr.co.fixlog.util.TokenManager
import kr.co.fixlog.util.WorkspaceManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 모든 API 요청에 Authorization: Bearer {accessToken} 및 X-Workspace-Id 헤더를 자동 부착한다.
 *
 * - 저장된 토큰이 없으면 Authorization 헤더 생략 (서버가 401로 응답하면 호출 측에서 재로그인 유도)
 * - 선택된 협업 워크스페이스가 있으면 X-Workspace-Id를 부착한다(§1.2). 개인 WS/미선택이면 생략 → 서버가 개인 WS로 처리.
 * - 401(accessToken 만료) 시 자동 재발급/재시도는 [TokenAuthenticator]가 담당한다.
 */
class AuthInterceptor(private val context: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()

        val token = TokenManager.getAccessToken(context)
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }

        // 협업 워크스페이스가 선택된 경우에만 헤더를 부착한다(개인 WS는 헤더 미포함).
        val workspaceId = WorkspaceManager.getWorkspaceHeader(context)
        if (!workspaceId.isNullOrBlank()) {
            builder.header("X-Workspace-Id", workspaceId)
        }

        return chain.proceed(builder.build())
    }
}
