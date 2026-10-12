package kr.co.fixlog.util

import android.content.Context
import kr.co.fixlog.R
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 버전 기록 시간 표기(웹 DocumentHistorySidePanel과 동일 규칙).
 *  - 상대: 방금 전 / n분 전 / n시간 전 / n일 전, 7일이 지나면 절대 시각
 *  - 절대: 기기 시간대 기준 "yyyy년 M월 d일 HH:mm"
 * 서버 시각은 ISO-8601(UTC)이다. 파싱 실패 시 원문을 그대로 돌려준다.
 */
object HistoryTime {

    fun relative(context: Context, raw: String?): String {
        val dt = parse(raw) ?: return raw.orEmpty()
        val diff = Duration.between(dt.toInstant(), java.time.Instant.now())
        val minutes = diff.toMinutes()
        return when {
            minutes < 1 -> context.getString(R.string.history_just_now)
            minutes < 60 -> context.getString(R.string.history_minutes_ago, minutes)
            minutes < 60 * 24 -> context.getString(R.string.history_hours_ago, minutes / 60)
            minutes < 60 * 24 * 7 -> context.getString(R.string.history_days_ago, minutes / (60 * 24))
            else -> absolute(context, raw)
        }
    }

    fun absolute(context: Context, raw: String?): String {
        val dt = parse(raw) ?: return raw.orEmpty()
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return dt.atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern(context.getString(R.string.history_datetime_pattern), locale))
    }

    private fun parse(raw: String?): OffsetDateTime? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
}
