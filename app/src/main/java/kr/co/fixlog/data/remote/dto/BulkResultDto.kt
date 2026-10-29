package kr.co.fixlog.data.remote.dto

/** 일괄 작업(/api/bulk) 결과. 일부만 성공할 수 있다(서버 BulkOperationResultDto). */
data class BulkResultDto(
    val totalRequested: Int,
    val succeededCount: Int,
    val failedCount: Int
)
