package kr.co.fixlog.util

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import kr.co.fixlog.activity.GoogleLoginActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 세션 만료(refreshToken까지 무효)를 앱 전역에서 처리하는 단일 진입점.
 *
 * FRONTEND_API_GUIDE §8의 401 처리 흐름 중 "재발급 실패(refreshToken도 만료) → 로그인 화면으로 이동"을 담당한다.
 * [TokenAuthenticator]가 refresh를 최종 실패로 판단하면 [notifySessionExpired]를 호출한다.
 *
 * 동작:
 *  - 저장된 토큰을 모두 지운다.
 *  - 백 스택을 비우고 [GoogleLoginActivity]를 새 태스크로 띄운다(뒤로가기로 만료된 화면에 복귀 불가).
 *  - 여러 API 호출이 동시에 401→refresh 실패를 겪어도 로그인 화면이 중복 실행되지 않도록 가드한다.
 *
 * 실행 컨텍스트: [TokenAuthenticator]는 OkHttp 백그라운드 스레드에서 동작하고 앱은 포그라운드 상태이므로,
 * Application context로 Activity를 새 태스크로 띄우는 것이 허용된다. 화면 전환은 메인 스레드에서 수행한다.
 */
object SessionManager {
    private const val TAG = "SessionManager"

    @Volatile
    private var appContext: Context? = null

    // 로그인 화면 중복 실행 방지 플래그. 로그인 화면 진입 시 [reset]으로 해제한다.
    private val redirecting = AtomicBoolean(false)

    /** [kr.co.fixlog.FixLogApp.onCreate]에서 Application context를 주입한다. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * refreshToken까지 만료/무효 → 저장된 토큰 삭제 후 로그인 화면으로 강제 이동.
     * 이미 이동이 진행 중이면(다른 요청이 먼저 트리거) 중복 실행하지 않는다.
     */
    fun notifySessionExpired() {
        val ctx = appContext ?: run {
            Log.w(TAG, "SessionManager.init 미호출 → 세션 만료 처리 불가")
            return
        }
        TokenManager.clear(ctx)
        WorkspaceManager.clear(ctx)

        if (!redirecting.compareAndSet(false, true)) {
            Log.d(TAG, "이미 로그인 화면으로 이동 중 → 중복 실행 방지")
            return
        }

        Handler(Looper.getMainLooper()).post {
            Log.d(TAG, "세션 만료 → 로그인 화면으로 이동")
            val intent = Intent(ctx, GoogleLoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            ctx.startActivity(intent)
        }
    }

    /**
     * 로그인 화면 진입 시 호출해 중복 가드를 해제한다.
     * 재로그인 이후 다시 세션이 만료되면 정상적으로 로그인 화면으로 이동할 수 있게 한다.
     */
    fun reset() {
        redirecting.set(false)
    }
}
