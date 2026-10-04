package kr.co.fixlog.util

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * AI 답변 등에서 쓰는 경량 Markdown → Spanned 변환기.
 * 외부 의존성 없이 흔한 서브셋을 처리한다:
 *  - 제목(#, ##, ###)
 *  - 굵게(**  __), 기울임(*  _), 취소선(~~), 인라인 코드(`)
 *  - 코드펜스(``` ... ```)
 *  - 글머리 기호 목록(-, *, +), 번호 목록(1.)
 *  - 인용(>) , 수평선(---, ***)
 *  - 링크 [텍스트](url) → 텍스트만 표시
 *
 * 완전한 파서는 아니며, 표/중첩 등 복잡한 문법은 원문에 가깝게(마커만 정리) 표시한다.
 */
object Markdown {

    private const val CODE_BG = 0xFFEFEFF1.toInt()
    private const val CODE_FG = 0xFF9333EA.toInt()

    fun render(source: String?): CharSequence {
        if (source.isNullOrBlank()) return ""
        val normalized = source.replace("\r\n", "\n").replace("\r", "\n")
        val out = SpannableStringBuilder()

        val lines = normalized.split("\n")
        var i = 0
        var inCode = false
        val codeBuffer = StringBuilder()

        fun appendCodeBlock() {
            if (codeBuffer.isEmpty()) return
            val start = out.length
            out.append(codeBuffer.toString().trimEnd('\n'))
            val end = out.length
            out.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(BackgroundColorSpan(CODE_BG), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(LeadingMarginSpan.Standard(dpFake(8)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            codeBuffer.setLength(0)
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // 코드펜스 토글
            if (trimmed.startsWith("```")) {
                if (inCode) {
                    appendCodeBlock()
                    inCode = false
                } else {
                    inCode = true
                }
                i++
                continue
            }
            if (inCode) {
                codeBuffer.append(line).append('\n')
                i++
                continue
            }

            if (out.isNotEmpty()) out.append("\n")

            when {
                // 수평선
                trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                    val s = out.length
                    out.append("──────────")
                    out.setSpan(ForegroundColorSpan(Color.LTGRAY), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                // 제목
                trimmed.startsWith("### ") -> appendHeading(out, trimmed.removePrefix("### "), 1.1f)
                trimmed.startsWith("## ") -> appendHeading(out, trimmed.removePrefix("## "), 1.25f)
                trimmed.startsWith("# ") -> appendHeading(out, trimmed.removePrefix("# "), 1.4f)
                // 인용
                trimmed.startsWith("> ") -> {
                    val s = out.length
                    out.append("▎ ")
                    appendInline(out, trimmed.removePrefix("> "))
                    out.setSpan(ForegroundColorSpan(0xFF6B7280.toInt()), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                // 글머리 기호 목록
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                    out.append("•  ")
                    appendInline(out, trimmed.substring(2))
                }
                // 번호 목록(예: "1. 항목") → 그대로 유지하되 인라인 처리
                Regex("^\\d+\\.\\s+.*").matches(trimmed) -> {
                    val dot = trimmed.indexOf('.')
                    out.append(trimmed.substring(0, dot + 1)).append("  ")
                    appendInline(out, trimmed.substring(dot + 1).trimStart())
                }
                else -> appendInline(out, line)
            }
            i++
        }
        // 닫히지 않은 코드펜스 방어
        if (inCode) appendCodeBlock()

        return out
    }

    private fun appendHeading(out: SpannableStringBuilder, text: String, scale: Float) {
        val s = out.length
        appendInline(out, text)
        val e = out.length
        out.setSpan(StyleSpan(Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(RelativeSizeSpan(scale), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** 한 줄의 인라인 마크다운(굵게/기울임/코드/취소선/링크)을 파싱해 [out]에 추가한다. */
    private fun appendInline(out: SpannableStringBuilder, text: String) {
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                // 인라인 코드
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close > i) {
                        val s = out.length
                        out.append(text.substring(i + 1, close))
                        out.setSpan(TypefaceSpan("monospace"), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(BackgroundColorSpan(CODE_BG), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(ForegroundColorSpan(CODE_FG), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = close + 1
                    } else { out.append(c); i++ }
                }
                // 굵게 ** 또는 __
                (text.startsWith("**", i) || text.startsWith("__", i)) -> {
                    val marker = text.substring(i, i + 2)
                    val close = text.indexOf(marker, i + 2)
                    if (close > i) {
                        val s = out.length
                        appendInline(out, text.substring(i + 2, close))
                        out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = close + 2
                    } else { out.append(c); i++ }
                }
                // 취소선 ~~
                text.startsWith("~~", i) -> {
                    val close = text.indexOf("~~", i + 2)
                    if (close > i) {
                        val s = out.length
                        appendInline(out, text.substring(i + 2, close))
                        out.setSpan(StrikethroughSpan(), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = close + 2
                    } else { out.append(c); i++ }
                }
                // 기울임 * 또는 _ (단일). 앞뒤가 공백이 아닌 경우에만 마커로 처리.
                (c == '*' || c == '_') -> {
                    val close = findSingleEmphasisClose(text, i, c)
                    if (close > i) {
                        val s = out.length
                        appendInline(out, text.substring(i + 1, close))
                        out.setSpan(StyleSpan(Typeface.ITALIC), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = close + 1
                    } else { out.append(c); i++ }
                }
                // 링크 [텍스트](url) → 텍스트만
                c == '[' -> {
                    val link = matchLink(text, i)
                    if (link != null) {
                        val (label, _, next) = link
                        val s = out.length
                        appendInline(out, label)
                        out.setSpan(ForegroundColorSpan(0xFF2563EB.toInt()), s, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = next
                    } else { out.append(c); i++ }
                }
                else -> { out.append(c); i++ }
            }
        }
    }

    /** 단일 * / _ 강조의 닫는 위치. 없으면 -1. (** 는 위에서 먼저 처리되므로 여기선 단일만) */
    private fun findSingleEmphasisClose(text: String, open: Int, marker: Char): Int {
        var j = open + 1
        if (j < text.length && text[j] == marker) return -1 // ** 은 별도 처리
        while (j < text.length) {
            if (text[j] == marker) return j
            j++
        }
        return -1
    }

    /** [i]가 '['일 때 [텍스트](url) 매칭. 성공 시 (label, url, nextIndex). */
    private fun matchLink(text: String, i: Int): Triple<String, String, Int>? {
        val closeBracket = text.indexOf(']', i + 1)
        if (closeBracket < 0 || closeBracket + 1 >= text.length || text[closeBracket + 1] != '(') return null
        val closeParen = text.indexOf(')', closeBracket + 2)
        if (closeParen < 0) return null
        val label = text.substring(i + 1, closeBracket)
        val url = text.substring(closeBracket + 2, closeParen)
        return Triple(label, url, closeParen + 1)
    }

    /** LeadingMarginSpan에 넣을 대략적인 px(밀도 접근 없이 상수 근사). */
    private fun dpFake(dp: Int): Int = dp * 3
}
