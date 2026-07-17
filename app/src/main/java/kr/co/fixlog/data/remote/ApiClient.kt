package kr.co.fixlog.data.remote

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * OkHttp + Moshi 싱글톤. FixLogApp.onCreate에서 [init]을 호출해 Application context를 주입한다.
 *
 * 사용:
 *   val request = Request.Builder().url(ApiClient.BASE_URL + "/api/folders").build()
 *   val response = ApiClient.httpClient.newCall(request).execute()
 */
object ApiClient {
    // TODO: 하드코딩 — 서버 베이스 URL.
    //  - 실기기 + adb reverse 로컬: "http://localhost:8080/fixlog"  ← 현재(로컬 개발)
    //    사전 준비: PC에서 `adb reverse tcp:8080 tcp:8080` 실행 → 기기의 localhost:8080이 PC로 포워딩됨.
    //  - 에뮬레이터(AVD) 로컬: "http://10.0.2.2:8080/fixlog"
    //  - 운영(HTTPS): "https://fixlog.art/fixlog"
    //  - 실기기 + 같은 Wi-Fi (LAN) 로컬: "http://<PC LAN IP>:8080/fixlog"
    //  BuildConfig.buildConfigField 또는 build flavor(prod/dev)로 분리 권장.
    const val BASE_URL = "http://localhost:8080/fixlog"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    val httpClient: OkHttpClient by lazy {
        val ctx = appContext
            ?: error("ApiClient.init(context) must be called from Application.onCreate()")
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(ctx))
            // 401(accessToken 만료) 시 refreshToken으로 재발급 후 자동 재시도.
            .authenticator(TokenAuthenticator(ctx))
            .addInterceptor(HttpLoggingInterceptor().apply {
                // TODO: 운영 빌드에선 BuildConfig.DEBUG 가드로 NONE 또는 BASIC으로 낮출 것.
                level = HttpLoggingInterceptor.Level.BODY
            })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * AI 호출 전용 클라이언트. [httpClient]에서 파생(커넥션 풀/디스패처/인터셉터 공유)하되
     * 읽기 타임아웃만 길게 잡는다. AI 요약/질의응답 엔드포인트는 임베딩+생성에 수십 초가 걸릴 수 있어
     * 기본 15초 readTimeout으로는 SocketTimeoutException으로 실패한다.
     */
    val aiHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(70, TimeUnit.SECONDS)
            .build()
    }
}
