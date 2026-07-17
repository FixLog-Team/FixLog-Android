package kr.co.fixlog.data.format

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * BlockNote(클라이언트 에디터) 블록 포맷 ↔ Editor.js(서버가 허용/추출하는) 블록 포맷 변환기.
 *
 * 서버(FixLog-Server)의 blocks 검증/추출은 Editor.js 스키마만 이해한다
 * (허용 type: paragraph/header/list/code/image/table, 텍스트는 data.text/items/code).
 * 반면 클라이언트 에디터는 BlockNote 스키마(type: heading/bulletListItem/codeBlock…,
 * 텍스트는 content[].text)를 쓴다. 이 불일치 때문에 BlockNote 문서를 그대로 저장하면
 * 서버가 400(허용되지 않는 block type) 또는 plainText 빈 값을 반환한다.
 *
 * 그래서 **저장 시 BlockNote → Editor.js**, **조회 시 Editor.js → BlockNote** 로 변환한다.
 *
 * 설계 원칙
 * - 포맷 자동 감지: 이미 대상 포맷인 블록은 그대로 통과시킨다(기존 BlockNote 저장 문서 보호 + 이중 변환 방지).
 * - table/image 는 type 이름이 두 스키마에서 동일하고 서버 검증도 통과하므로 원본 그대로 통과시킨다.
 * - 실패 시(파싱 불가 등) 원본 문자열을 그대로 반환한다(변환기 버그가 기능을 죽이지 않도록).
 *
 * 손실(lossy) 주의: Editor.js 허용 type 집합이 BlockNote 보다 좁아 아래는 왕복 시 타입이 바뀐다.
 * - checkListItem → list(unordered) → bulletListItem (체크 상태 소실)
 * - quote → paragraph
 * - 리스트 중첩(children)은 평탄화된다.
 * 인라인 서식(굵게/기울임/밑줄/취소선/코드)은 Editor.js text 의 HTML 태그로 보존/복원한다.
 */
object BlockFormatConverter {

    private val LIST_UNORDERED = setOf("bulletListItem", "checkListItem")

    // ---------------------------------------------------------------------
    // BlockNote → Editor.js  (저장 시)
    // ---------------------------------------------------------------------
    fun blockNoteToEditorJs(json: String): String {
        return try {
            val blocks = parseBlocks(json) ?: return json
            val out = JSONArray()
            var i = 0
            while (i < blocks.length()) {
                val b = blocks.optJSONObject(i)
                if (b == null) { i++; continue }
                if (isEditorJsBlock(b)) { out.put(b); i++; continue }

                when (b.optString("type")) {
                    "bulletListItem", "checkListItem" -> {
                        val items = JSONArray()
                        while (i < blocks.length()) {
                            val cur = blocks.optJSONObject(i) ?: break
                            if (cur.optString("type") !in LIST_UNORDERED) break
                            appendListItem(cur, items)
                            i++
                        }
                        out.put(editorList("unordered", items))
                    }
                    "numberedListItem" -> {
                        val items = JSONArray()
                        while (i < blocks.length()) {
                            val cur = blocks.optJSONObject(i) ?: break
                            if (cur.optString("type") != "numberedListItem") break
                            appendListItem(cur, items)
                            i++
                        }
                        out.put(editorList("ordered", items))
                    }
                    "heading" -> {
                        val level = b.optJSONObject("props")?.optInt("level", 1) ?: 1
                        out.put(editorBlock("header", JSONObject()
                            .put("text", inlineToHtml(b.optJSONArray("content")))
                            .put("level", level)))
                        i++
                    }
                    "codeBlock" -> {
                        val lang = b.optJSONObject("props")?.optString("language", "") ?: ""
                        out.put(editorBlock("code", JSONObject()
                            .put("code", inlineToPlain(b.optJSONArray("content")))
                            .put("language", lang)))
                        i++
                    }
                    // type 이름이 동일하고 서버 검증을 통과하므로 원본 그대로 둔다.
                    "table", "image" -> { out.put(b); i++ }
                    // paragraph, quote, 그 외 알 수 없는 타입 → paragraph 로 (본문 텍스트는 보존)
                    else -> {
                        out.put(editorBlock("paragraph", JSONObject()
                            .put("text", inlineToHtml(b.optJSONArray("content")))))
                        i++
                    }
                }
            }
            out.toString()
        } catch (e: Exception) {
            json
        }
    }

    // ---------------------------------------------------------------------
    // Editor.js → BlockNote  (조회 시)
    // ---------------------------------------------------------------------
    fun editorJsToBlockNote(json: String): String {
        return try {
            val blocks = parseBlocks(json) ?: return json
            val out = JSONArray()
            for (i in 0 until blocks.length()) {
                val b = blocks.optJSONObject(i) ?: continue
                if (isBlockNoteBlock(b)) { out.put(b); continue }

                val data = b.optJSONObject("data") ?: JSONObject()
                when (b.optString("type")) {
                    "header" -> out.put(bnBlock("heading",
                        htmlToInline(data.optString("text", "")),
                        defaultProps().put("level", data.optInt("level", 1))))
                    "paragraph" -> out.put(bnBlock("paragraph",
                        htmlToInline(data.optString("text", "")), defaultProps()))
                    "list" -> {
                        val bnType =
                            if (data.optString("style", "unordered") == "ordered") "numberedListItem"
                            else "bulletListItem"
                        val items = data.optJSONArray("items") ?: JSONArray()
                        for (j in 0 until items.length()) {
                            out.put(bnBlock(bnType, htmlToInline(items.optString(j, "")), defaultProps()))
                        }
                    }
                    "code" -> out.put(bnBlock("codeBlock",
                        JSONArray().put(textNode(data.optString("code", ""))),
                        JSONObject().put("language", data.optString("language", ""))))
                    "table", "image" -> out.put(b)
                    else -> out.put(bnBlock("paragraph",
                        htmlToInline(data.optString("text", "")), defaultProps()))
                }
            }
            out.toString()
        } catch (e: Exception) {
            json
        }
    }

    // ---------------------------------------------------------------------
    // 포맷 감지
    // ---------------------------------------------------------------------
    /** Editor.js 블록: data 객체가 있고 content(=BlockNote 인라인)가 없다. */
    private fun isEditorJsBlock(b: JSONObject): Boolean =
        b.has("data") && !b.has("content")

    /** BlockNote 블록: content 나 props 가 있고 data 가 없다. */
    private fun isBlockNoteBlock(b: JSONObject): Boolean =
        !b.has("data") && (b.has("content") || b.has("props"))

    // ---------------------------------------------------------------------
    // 리스트 아이템 수집 (중첩 children 은 평탄화)
    // ---------------------------------------------------------------------
    private fun appendListItem(block: JSONObject, items: JSONArray) {
        items.put(inlineToHtml(block.optJSONArray("content")))
        val children = block.optJSONArray("children") ?: return
        for (i in 0 until children.length()) {
            val c = children.optJSONObject(i) ?: continue
            if (c.optString("type") in LIST_UNORDERED || c.optString("type") == "numberedListItem") {
                appendListItem(c, items)
            } else {
                items.put(inlineToHtml(c.optJSONArray("content")))
            }
        }
    }

    // ---------------------------------------------------------------------
    // Editor.js 블록 빌더
    // ---------------------------------------------------------------------
    private fun editorBlock(type: String, data: JSONObject): JSONObject =
        JSONObject().put("type", type).put("data", data)

    private fun editorList(style: String, items: JSONArray): JSONObject =
        editorBlock("list", JSONObject().put("style", style).put("items", items))

    // ---------------------------------------------------------------------
    // BlockNote 블록 빌더
    // ---------------------------------------------------------------------
    private fun defaultProps(): JSONObject = JSONObject()
        .put("textColor", "default")
        .put("backgroundColor", "default")
        .put("textAlignment", "left")

    private fun textNode(text: String, styles: JSONObject = JSONObject()): JSONObject =
        JSONObject().put("type", "text").put("text", text).put("styles", styles)

    private fun bnBlock(type: String, content: JSONArray, props: JSONObject): JSONObject =
        JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("type", type)
            .put("props", props)
            .put("content", content)
            .put("children", JSONArray())

    // ---------------------------------------------------------------------
    // 인라인 변환:  BlockNote content[] ↔ HTML 문자열(Editor.js text)
    // ---------------------------------------------------------------------
    private val STYLE_TAGS = listOf(
        "bold" to "b", "italic" to "i", "underline" to "u", "strike" to "s", "code" to "code"
    )
    private val TAG_TO_STYLE = mapOf(
        "b" to "bold", "strong" to "bold",
        "i" to "italic", "em" to "italic",
        "u" to "underline",
        "s" to "strike", "strike" to "strike", "del" to "strike",
        "code" to "code"
    )

    /** BlockNote 인라인 배열 → HTML(서식 태그 포함). */
    private fun inlineToHtml(content: JSONArray?): String {
        if (content == null) return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val node = content.optJSONObject(i) ?: continue
            when (node.optString("type")) {
                "text" -> {
                    val styles = node.optJSONObject("styles") ?: JSONObject()
                    var open = ""
                    var close = ""
                    for ((style, tag) in STYLE_TAGS) {
                        if (styles.optBoolean(style, false)) {
                            open += "<$tag>"; close = "</$tag>$close"
                        }
                    }
                    sb.append(open).append(escapeHtml(node.optString("text", ""))).append(close)
                }
                // link 등은 안쪽 텍스트만 보존(하이퍼링크 URL 은 소실 — lossy).
                "link" -> sb.append(escapeHtml(inlineToPlain(node.optJSONArray("content"))))
            }
        }
        return sb.toString()
    }

    /** BlockNote 인라인 배열 → 순수 텍스트(코드블록 등, 서식 무시). */
    private fun inlineToPlain(content: JSONArray?): String {
        if (content == null) return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val node = content.optJSONObject(i) ?: continue
            when (node.optString("type")) {
                "text" -> sb.append(node.optString("text", ""))
                "link" -> sb.append(inlineToPlain(node.optJSONArray("content")))
            }
        }
        return sb.toString()
    }

    /** HTML(서식 태그 포함) → BlockNote 인라인 배열. 지원 태그: b/i/u/s/code(및 동의어). */
    private fun htmlToInline(html: String): JSONArray {
        val result = JSONArray()
        if (html.isEmpty()) return result
        val active = HashSet<String>()
        val buf = StringBuilder()

        fun flush() {
            if (buf.isEmpty()) return
            val styles = JSONObject()
            for (s in active) styles.put(s, true)
            result.put(textNode(unescapeHtml(buf.toString()), styles))
            buf.setLength(0)
        }

        var i = 0
        while (i < html.length) {
            val c = html[i]
            if (c == '<') {
                val end = html.indexOf('>', i)
                if (end < 0) { buf.append(c); i++; continue }
                val raw = html.substring(i + 1, end).trim()
                flush()
                val closing = raw.startsWith("/")
                val name = (if (closing) raw.substring(1) else raw)
                    .takeWhile { it.isLetter() }
                    .lowercase()
                val style = TAG_TO_STYLE[name]
                if (style != null) {
                    if (closing) active.remove(style) else active.add(style)
                }
                i = end + 1
            } else {
                buf.append(c); i++
            }
        }
        flush()
        // 빈 결과면 최소 빈 텍스트 노드 하나(BlockNote 는 빈 content 도 허용하지만 안전하게).
        return result
    }

    // ---------------------------------------------------------------------
    // HTML escape / unescape
    // ---------------------------------------------------------------------
    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun unescapeHtml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    // ---------------------------------------------------------------------
    // 공통 파싱: 최상위가 배열이거나 { "blocks": [...] } 객체인 경우를 모두 처리.
    // ---------------------------------------------------------------------
    private fun parseBlocks(json: String): JSONArray? {
        val trimmed = json.trim()
        if (trimmed.isEmpty() || trimmed == "null") return null
        return try {
            if (trimmed.startsWith("[")) JSONArray(trimmed)
            else JSONObject(trimmed).optJSONArray("blocks")
        } catch (e: Exception) {
            null
        }
    }
}
