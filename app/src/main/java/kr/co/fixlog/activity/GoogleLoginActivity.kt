package kr.co.fixlog.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kr.co.fixlog.R
import kr.co.fixlog.databinding.ActivityGoogleLoginBinding
import kr.co.fixlog.util.SessionManager
import kr.co.fixlog.util.TokenManager

/**
 * 구글 OAuth2 로그인 진입 화면.
 *
 * 로그인 흐름(remote 서버가 지원하는 `/login/swag` 방식):
 *  1) 사용자가 [btn_login] 탭 → 앱 내 WebView로 서버의 `/login/swag` 진입 URL을 연다.
 *     (`/login/swag`은 서버 세션에 SWAG 플래그를 세워, 로그인 성공 후 `/login/swag/callback`으로 토큰을 실어 돌려준다.)
 *  2) WebView에서 구글 로그인 화면이 표시되고, 사용자가 인증을 완료한다.
 *  3) 서버가 `${AUTH_BASE_URL}/login/swag/callback?accessToken=...&refreshToken=...` 로 리다이렉트한다.
 *  4) WebViewClient가 이 콜백 URL을 가로채 쿼리에서 토큰을 추출 → TokenManager에 저장 → HomeActivity로 이동.
 *
 * 참고: remote 서버에는 모바일 딥링크(`/login/app` → myapp://callback)가 없으므로, http 콜백 URL을
 *       WebView에서 직접 가로채는 방식으로 서버 수정 없이 자동 로그인한다.
 *       (구글이 WebView 내 OAuth를 막는 경우 User-Agent 조정으로 완화하며, 그래도 막히면 DEV_ACCESS_TOKEN 폴백 사용.)
 */
class GoogleLoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGoogleLoginBinding
    private var loginWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityGoogleLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 세션 만료로 이 화면에 되돌아온 경우, 다음 만료도 정상 처리되도록 중복 가드를 해제한다.
        SessionManager.reset()

        // 테스트용 로그인 스킵: SKIP_LOGIN이 true면 인증을 완전히 건너뛰고 바로 메인으로 진입한다.
        if (SKIP_LOGIN) {
            Log.e("test", "SKIP_LOGIN — 인증 없이 바로 MainActivity 진입")
            goToMain()
            return
        }

        // 개발용 로그인 우회: DEV_ACCESS_TOKEN이 채워져 있으면 OAuth 흐름을 건너뛰고 해당 토큰을 저장한 뒤 진입한다.
        // 토큰 발급: 브라우저로 ${AUTH_BASE_URL}/login/swag 접속 → 구글 로그인 →
        //          /login/swag/callback 응답에 표시된 accessToken을 복사해 아래 상수에 붙여넣는다.
        if (DEV_ACCESS_TOKEN.isNotBlank()) {
            Log.e("test", "DEV bypass login — using hardcoded accessToken")
            TokenManager.saveTokens(this, DEV_ACCESS_TOKEN, DEV_REFRESH_TOKEN.ifBlank { null })
            goToMain()
            return
        }

        // 이미 로그인 상태면 로그인 화면을 보여주지 않고 바로 메인으로.
        if (TokenManager.isLoggedIn(this)) {
            goToMain()
            return
        }

        binding.btnLogin.setOnClickListener {
            launchGoogleLogin()
        }
    }

    /**
     * 앱 내 WebView로 서버의 `/login/swag` 로그인 진입 URL을 연다.
     * 로그인 성공 시 서버가 `/login/swag/callback?accessToken=..&refreshToken=..` 로 리다이렉트하며,
     * 이 URL을 [SwagCallbackClient]가 가로채 토큰을 저장한다.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun launchGoogleLogin() {
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 구글은 기본 WebView UA("; wv")의 OAuth를 차단하므로, 표준 크롬 UA로 위장한다.
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36"
            webViewClient = SwagCallbackClient()
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        loginWebView = webView
        setContentView(webView)
        webView.loadUrl("$AUTH_BASE_URL/login/swag")
    }

    /** `/login/swag/callback` 리다이렉트를 가로채 토큰을 추출·저장하고 메인으로 이동한다. */
    private inner class SwagCallbackClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleUrl(request.url)

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleUrl(Uri.parse(url))

        private fun handleUrl(uri: Uri): Boolean {
            // 콜백이 아니면 WebView가 그대로 로드하도록 둔다(구글 로그인 페이지 등).
            if (!uri.toString().startsWith("$AUTH_BASE_URL$SWAG_CALLBACK_PATH")) return false

            Log.e("test", "swag callback uri: $uri")
            val error = uri.getQueryParameter("error")
            if (!error.isNullOrBlank()) {
                Log.e(TAG, "login error: $error")
                Toast.makeText(this@GoogleLoginActivity, "로그인 실패: $error", Toast.LENGTH_SHORT).show()
                restoreLoginScreen()
                return true
            }

            val accessToken = uri.getQueryParameter("accessToken")
            if (accessToken.isNullOrBlank()) {
                Log.e("test", "login failed: no token in callback")
                Toast.makeText(this@GoogleLoginActivity, "로그인 실패: 토큰을 받지 못했습니다", Toast.LENGTH_SHORT).show()
                restoreLoginScreen()
                return true
            }

            val refreshToken = uri.getQueryParameter("refreshToken")
            TokenManager.saveTokens(this@GoogleLoginActivity, accessToken, refreshToken)
            Log.d(TAG, "token saved (refresh=${refreshToken != null})")
            goToMain()
            return true
        }
    }

    /** 로그인 실패 시 WebView를 닫고 로그인 화면으로 되돌린다. */
    private fun restoreLoginScreen() {
        setContentView(binding.root)
        loginWebView?.destroy()
        loginWebView = null
    }

    override fun onBackPressed() {
        val webView = loginWebView
        when {
            webView != null && webView.canGoBack() -> webView.goBack()
            webView != null -> restoreLoginScreen()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        loginWebView?.destroy()
        loginWebView = null
        super.onDestroy()
    }

    private fun goToMain() {
        val i = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(i)
        finish()
    }

    companion object {
        private const val TAG = "GoogleLogin"

        // 테스트용 로그인 스킵 스위치. true면 로그인 화면/인증을 건너뛰고 바로 메인으로 진입한다.
        private const val SKIP_LOGIN = false

        // 개발용 로그인 우회 토큰(폴백). WebView OAuth가 막히는 경우에만 사용.
        //  발급 방법: 브라우저에서 ${AUTH_BASE_URL}/login/swag 로 로그인 → /login/swag/callback
        //  응답의 accessToken(+refreshToken)을 아래에 붙여넣으면 로그인 화면을 건너뛴다.
        //  운영 배포 전 반드시 빈 문자열로 되돌릴 것.
        private const val DEV_ACCESS_TOKEN = ""
        private const val DEV_REFRESH_TOKEN = ""

        // 환경별 서버 주소.
        //  - 실기기 + adb reverse 로컬: "http://localhost:8080/fixlog" (현재). 사전: `adb reverse tcp:8080 tcp:8080`.
        //  - 운영(HTTPS): "https://fixlog.art/fixlog"
        private const val AUTH_BASE_URL = "http://localhost:8080/fixlog"

        // remote 서버의 스웨거/앱 공용 로그인 콜백 경로(토큰이 쿼리에 실려 온다).
        private const val SWAG_CALLBACK_PATH = "/login/swag/callback"
    }
}
