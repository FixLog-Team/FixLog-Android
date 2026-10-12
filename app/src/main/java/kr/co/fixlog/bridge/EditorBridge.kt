package kr.co.fixlog.bridge

import android.webkit.JavascriptInterface

/**
 * JS bridge exposed to the editor WebView under the global name "Android".
 * All @JavascriptInterface methods run on a background thread; callers must
 * post UI-touching work to the main thread.
 */
class EditorBridge(
    private val onTitle: (String) -> Unit,
    private val onEditLabels: () -> Unit = {},
    private val onEditorReady: () -> Unit = {}
) {
    /** 본문 헤더(#doc-title) 편집 시 현재 제목 텍스트를 네이티브로 전달. */
    @JavascriptInterface
    fun onTitleChanged(title: String) = onTitle(title)

    /** 라벨 행의 수정/추가 버튼 클릭 시 네이티브 라벨 편집 다이얼로그를 연다. */
    @JavascriptInterface
    fun onEditLabels() = onEditLabels.invoke()

    /** 에디터(BlockNote) 마운트 완료 시 호출 → 네이티브 로딩 오버레이 제거. */
    @JavascriptInterface
    fun onEditorReady() = onEditorReady.invoke()
}