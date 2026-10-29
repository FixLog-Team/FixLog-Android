package kr.co.fixlog.data.remote.dto

/**
 * POST /api/images/upload 응답 result.
 * url 은 조회용 절대 API URL(서버가 Presigned URL 로 302 리다이렉트). 이 값을 그대로 image 블록에 저장한다.
 */
data class ImageUploadDto(
    val imageId: String,
    val url: String
)
