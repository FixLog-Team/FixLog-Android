package kr.co.fixlog.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 로그인 토큰(Access / Refresh) 영속화 유틸.
 *
 * 서버의 OAuth2LoginSuccessHandler가 콜백으로 내려준 토큰을 저장하고,
 * 이후 API 호출 시 꺼내 쓸 수 있도록 한다.
 *
 * 저장소는 AndroidX Security의 [EncryptedSharedPreferences]를 사용한다.
 *  - 값은 AES256-GCM으로 암호화되며, 마스터 키는 Android Keystore(하드웨어 보안 모듈)에 보관된다.
 *  - 따라서 기기 백업/루팅/파일 추출로 SharedPreferences 파일을 얻어도 토큰을 복호화할 수 없다.
 *  - 마스터 키가 Keystore에 있으므로 이 파일은 백업에서 제외한다(backup_rules.xml / data_extraction_rules.xml).
 */
object TokenManager {
    private const val TAG = "TokenManager"
    private const val PREF_NAME = "fixlog_auth"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val created = create(context.applicationContext)
            cached = created
            return created
        }
    }

    private fun create(appContext: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return try {
            build(appContext, masterKey)
        } catch (e: Exception) {
            // 마스터 키/keyset 불일치(예: 백업 복원, 키스토어 초기화)로 복호화가 불가능하면
            // 손상된 저장소를 삭제하고 재생성한다. 사용자는 재로그인만 하면 된다.
            Log.w(TAG, "EncryptedSharedPreferences 열기 실패 → 저장소 재생성", e)
            appContext.deleteSharedPreferences(PREF_NAME)
            build(appContext, masterKey)
        }
    }

    private fun build(appContext: Context, masterKey: MasterKey): SharedPreferences =
        EncryptedSharedPreferences.create(
            appContext,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    /**
     * 로그인 성공 시 받은 토큰을 저장한다.
     * refreshToken은 서버가 함께 내려준 경우에만 갱신한다(없으면 기존 값 유지).
     */
    fun saveTokens(context: Context, accessToken: String, refreshToken: String? = null) {
        prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .apply {
                if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken)
            }
            .apply()
    }

    /** API 호출 시 Authorization 헤더에 실어 보낼 토큰. */
    fun getAccessToken(context: Context): String? =
        prefs(context).getString(KEY_ACCESS_TOKEN, null)

    /** Access Token 만료 시 재발급 요청에 사용할 토큰. */
    fun getRefreshToken(context: Context): String? =
        prefs(context).getString(KEY_REFRESH_TOKEN, null)

    /** 저장된 access token이 있는지로 로그인 여부를 판단(만료 검증은 별도). */
    fun isLoggedIn(context: Context): Boolean = !getAccessToken(context).isNullOrBlank()

    /** 로그아웃 시 모든 토큰 삭제. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
