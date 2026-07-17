package kr.co.fixlog.util

import android.content.Context

/**
 * 로그인 토큰(Access / Refresh) 영속화 유틸.
 *
 * 서버의 OAuth2LoginSuccessHandler가 deep link로 내려준 토큰을 SharedPreferences에 저장하고,
 * 이후 API 호출 시 꺼내 쓸 수 있도록 한다.
 *
 * NOTE: SharedPreferences는 평문 저장이다. 운영 단계에서는 androidx.security의 EncryptedSharedPreferences
 *       또는 Keystore 기반 암호화로 교체할 것을 권장한다.
 */
object TokenManager {
    private const val PREF_NAME = "fixlog_auth"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"

    /**
     * 로그인 성공 시 받은 토큰을 저장한다.
     * refreshToken은 서버가 함께 내려준 경우에만 갱신한다(없으면 기존 값 유지).
     */
    fun saveTokens(context: Context, accessToken: String, refreshToken: String? = null) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .apply {
                if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken)
            }
            .apply()
    }

    /** API 호출 시 Authorization 헤더에 실어 보낼 토큰. */
    fun getAccessToken(context: Context): String? =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACCESS_TOKEN, null)

    /** Access Token 만료 시 재발급 요청에 사용할 토큰. */
    fun getRefreshToken(context: Context): String? =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_REFRESH_TOKEN, null)

    /** 저장된 access token이 있는지로 로그인 여부를 판단(만료 검증은 별도). */
    fun isLoggedIn(context: Context): Boolean = !getAccessToken(context).isNullOrBlank()

    /** 로그아웃 시 모든 토큰 삭제. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
