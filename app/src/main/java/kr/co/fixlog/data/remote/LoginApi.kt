package kr.co.fixlog.data.remote

/**
 * login-controller 매핑.
 *
 *   GET /login → [loginUrl]
 *
 * 이 엔드포인트는 데이터 API가 아니라 OAuth 로그인 진입점(브라우저 리다이렉트)이다.
 * 따라서 OkHttp로 직접 호출하지 않고, CustomTabsIntent/WebView로 열어야 할 URL만 제공한다.
 * (실제 구글 인증 시작은 GoogleLoginActivity에서 /oauth2/authorization/google 로 진입)
 */
object LoginApi {
    /** 서버 로그인 진입 페이지 URL. 브라우저/CustomTab으로 열어 사용한다. */
    fun loginUrl(): String = "${ApiClient.BASE_URL}/login"
}