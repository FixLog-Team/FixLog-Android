package kr.co.fixlog.data.remote.dto

/**
 * POST /auth/token/refresh 결과에서 추출한 토큰 쌍.
 *
 * 서버 응답의 result 스키마가 Swagger에 구체적으로 노출되어 있지 않아,
 * [kr.co.fixlog.data.remote.AuthApi.refresh]에서 result JSON의 accessToken / refreshToken
 * 필드를 직접 파싱해 채운다. 서버 필드명이 다르면 AuthApi 파싱부만 맞추면 된다.
 */
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String?
)