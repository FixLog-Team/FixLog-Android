package kr.co.fixlog.activity

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.data.remote.ApiClient
import kr.co.fixlog.data.remote.AuthApi
import kr.co.fixlog.databinding.ActivityGoogleLoginBinding
import kr.co.fixlog.util.SessionManager
import kr.co.fixlog.util.TokenManager
import java.util.UUID

/**
 * 구글 OAuth2 로그인 진입 화면 — Google 권장 방식(RFC 8252, Custom Tabs + 백엔드 브로커드).
 *
 * 흐름(상세 계약: docs/OAUTH_CUSTOM_TABS_전환.md, docs/서버_로그인_변경_요청.md):
 *  1) 로그인 버튼 → state(nonce) 생성·저장 후 Custom Tab으로 서버 로그인 시작 URL을 연다.
 *     `{BASE}/login/app?redirect_uri=kr.co.fixlog://oauth2callback&state={state}`
 *  2) 시스템 브라우저에서 Google 로그인 → 서버가 code↔token 교환(PKCE는 서버 담당).
 *  3) 서버가 앱 딥링크로 1회용 code를 리다이렉트: `kr.co.fixlog://oauth2callback?code=..&state=..`
 *  4) singleTask 액티비티가 onNewIntent로 딥링크를 받아 state 검증 → `POST /auth/exchange`로 토큰 교환 → 저장 → 홈.
 *
 * 토큰은 딥링크 URL에 실리지 않으며(1회용 code만 왕복), WebView OAuth 정책 제약도 피한다.
 */
class GoogleLoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGoogleLoginBinding

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

        // 콜백 딥링크로 (콜드 스타트) 진입한 경우 먼저 처리.
        if (handleAuthCallback(intent)) return

        // 개발용 로그인 우회: DEV_ACCESS_TOKEN이 채워져 있으면 그 토큰으로 바로 진입(운영 배포 전 반드시 비울 것).
        if (DEV_ACCESS_TOKEN.isNotBlank()) {
            TokenManager.saveTokens(this, DEV_ACCESS_TOKEN, DEV_REFRESH_TOKEN.ifBlank { null })
            goToMain()
            return
        }

        // 이미 로그인 상태면 바로 진입.
        if (TokenManager.isLoggedIn(this)) {
            goToMain()
            return
        }

        binding.btnLogin.setOnClickListener { startLogin() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCallback(intent)
    }

    /** Custom Tab으로 서버 로그인 시작 URL을 연다. state는 저장 후 콜백에서 검증한다. */
    private fun startLogin() {
        val state = UUID.randomUUID().toString()
        saveState(state)

        val url = Uri.parse("${ApiClient.BASE_URL}/login/app").buildUpon()
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("state", state)
            .build()

        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(this, url)
        } catch (e: ActivityNotFoundException) {
            // Custom Tabs/브라우저가 없을 때: 기본 브라우저로 폴백.
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url))
            } catch (e2: ActivityNotFoundException) {
                Log.w(TAG, "브라우저를 찾을 수 없음", e2)
                toast(getString(R.string.login_no_browser))
            }
        }
    }

    /**
     * 딥링크 콜백 처리. 우리 스킴이 아니면 false.
     * 성공: code → 토큰 교환. 실패: error 안내.
     */
    private fun handleAuthCallback(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != CALLBACK_SCHEME || uri.host != CALLBACK_HOST) return false

        // state 검증(CSRF 방지). 일치하지 않으면 무시.
        val returnedState = uri.getQueryParameter("state")
        val savedState = consumeState()
        if (savedState == null || returnedState != savedState) {
            Log.w(TAG, "state 불일치 → 콜백 무시")
            toast(getString(R.string.login_verify_failed))
            return true
        }

        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            Log.w(TAG, "login error: $error")
            toast(getString(R.string.login_failed_reason, error))
            return true
        }

        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            toast(getString(R.string.login_failed_no_code))
            return true
        }

        exchangeCode(code)
        return true
    }

    /** 1회용 code를 서버 JWT로 교환하고 저장 후 홈으로 이동. */
    private fun exchangeCode(code: String) {
        lifecycleScope.launch {
            runCatching { AuthApi.exchange(code) }
                .onSuccess { token ->
                    TokenManager.saveTokens(this@GoogleLoginActivity, token.accessToken, token.refreshToken)
                    Log.d(TAG, "token exchanged (refresh=${token.refreshToken != null})")
                    goToMain()
                }
                .onFailure { e ->
                    Log.w(TAG, "code 교환 실패", e)
                    toast(getString(R.string.login_failed_retry))
                }
        }
    }

    private fun goToMain() {
        // 로그인 후 하단 네비게이션의 첫 탭("문서")으로 진입한다.
        val i = Intent(this, DocumentsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(i)
        finish()
    }

    // --- state 저장/소비 ---

    private fun saveState(state: String) {
        getSharedPreferences(PREF_OAUTH, MODE_PRIVATE).edit().putString(KEY_STATE, state).apply()
    }

    private fun consumeState(): String? {
        val prefs = getSharedPreferences(PREF_OAUTH, MODE_PRIVATE)
        val state = prefs.getString(KEY_STATE, null)
        prefs.edit().remove(KEY_STATE).apply()
        return state
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "GoogleLogin"

        // 개발용 로그인 우회 토큰(폴백). 운영 배포 전 반드시 빈 문자열로 되돌릴 것.
        private const val DEV_ACCESS_TOKEN = ""
        private const val DEV_REFRESH_TOKEN = ""

        // 서버 redirect_uri 화이트리스트와 정확히 일치해야 함(AndroidManifest 딥링크와도 동일).
        private const val CALLBACK_SCHEME = "kr.co.fixlog"
        private const val CALLBACK_HOST = "oauth2callback"
        private const val REDIRECT_URI = "$CALLBACK_SCHEME://$CALLBACK_HOST"

        private const val PREF_OAUTH = "fixlog_oauth"
        private const val KEY_STATE = "oauth_state"
    }
}
