package kr.co.fixlog

import android.app.Application
import kr.co.fixlog.data.remote.ApiClient
import kr.co.fixlog.util.SessionManager

/**
 * 앱 단위 초기화 진입점. AndroidManifest의 application 태그에 android:name=".FixLogApp"으로 등록.
 *
 * 책임:
 *  - [ApiClient]에 Application context 주입 (이후 OkHttpClient가 AuthInterceptor를 통해 토큰을 자동 부착)
 *  - [SessionManager]에 Application context 주입 (401 재발급 최종 실패 시 로그인 화면으로 전역 이동)
 */
class FixLogApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
        SessionManager.init(this)
    }
}
