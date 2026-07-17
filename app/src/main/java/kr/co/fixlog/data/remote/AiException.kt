package kr.co.fixlog.data.remote

import java.io.IOException

/**
 * AI 호출(/ai 엔드포인트) 실패를 사유별로 구분한 예외.
 *
 * UI는 [kr.co.fixlog.util.toAiMessage]로 각 타입을 사용자 문구로 변환한다.
 * 서버가 Gemini 429(할당량 초과)를 별도 코드로 노출하지 않고 HTTP 500 + code=UNKNOWN 으로
 * 뭉뚱그리므로(GlobalExceptionHandler), 앱은 "확정 429"를 알 수 없어 [Server]로 분류한다.
 */
sealed class AiException(cause: Throwable? = null) : IOException(cause) {
    /** 응답이 타임아웃보다 오래 걸림(AI 생성 지연). */
    class Timeout(cause: Throwable? = null) : AiException(cause)

    /** 서버에 연결 불가(서버 미기동, adb reverse 미설정, 네트워크 단절 등). */
    class Network(cause: Throwable? = null) : AiException(cause)

    /** 인증 만료/실패(401). 토큰 재발급까지 실패한 경우. */
    class Unauthorized : AiException()

    /** 대상 문서를 찾을 수 없음(404). 주로 요약에서 발생. */
    class NotFound : AiException()

    /** 잘못된 요청(400). 질문 길이 초과/형식 오류 등. */
    class InvalidRequest(val serverMessage: String?) : AiException()

    /** 서버 내부 오류(5xx). AI 사용량 한도(무료 할당량) 초과가 여기에 포함될 수 있다. */
    class Server(val serverCode: String?, val serverMessage: String?) : AiException()

    /** 요청은 성공했으나 결과가 비어 있음. */
    class Empty : AiException()
}