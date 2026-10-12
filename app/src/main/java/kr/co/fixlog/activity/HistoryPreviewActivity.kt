package kr.co.fixlog.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.bridge.EditorBridge
import kr.co.fixlog.data.remote.DocumentApi
import kr.co.fixlog.databinding.ActivityHistoryPreviewBinding
import kr.co.fixlog.util.HistoryTime
import kr.co.fixlog.util.LoadingIndicator
import kr.co.fixlog.util.OwnerNames
import kr.co.fixlog.util.ThemeManager
import org.json.JSONObject

/**
 * 버전 미리보기(읽기 전용) + 복원. 웹 DocumentEditorPage의 버전 미리보기 모드 대응.
 *  - 본문: GET /api/documents/{id}/history/{historyId} → 에디터(editor.html)를 읽기 전용으로 띄워 표시
 *  - 복원: 확인 팝업 → POST .../restore → RESULT_OK(+버전 번호)로 종료(목록 화면이 갱신/안내)
 */
class HistoryPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryPreviewBinding
    private lateinit var documentId: String
    private lateinit var historyId: String
    private var versionNo = 0

    /** 에디터 준비 + 본문 조회가 모두 끝나야 로딩 오버레이를 걷는다. */
    private var editorReady = false
    private var contentLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityHistoryPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        documentId = intent.getStringExtra(EXTRA_DOCUMENT_ID) ?: run { finish(); return }
        historyId = intent.getStringExtra(EXTRA_HISTORY_ID) ?: run { finish(); return }
        versionNo = intent.getIntExtra(EXTRA_VERSION_NO, 0)
        val createTime = intent.getStringExtra(EXTRA_CREATE_TIME)

        binding.btnBack.setOnClickListener { finish() }
        binding.tvBarTitle.text = getString(R.string.history_preview_title, versionNo)
        binding.tvBanner.text = getString(R.string.history_banner, versionNo, HistoryTime.absolute(this, createTime))
        binding.btnRestore.setOnClickListener { confirmRestore() }

        setupWebView()
        binding.webView.loadUrl("file:///android_asset/editor.html")
    }

    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val lang = resources.configuration.locales[0].language
                view?.evaluateJavascript("window.setLang && window.setLang('$lang');", null)
                val theme = when (ThemeManager.getMode(this@HistoryPreviewActivity)) {
                    ThemeManager.LIGHT -> "light"
                    ThemeManager.DARK -> "dark"
                    else -> "system"
                }
                view?.evaluateJavascript("window.setTheme && window.setTheme('$theme');", null)
                view?.evaluateJavascript("window.setReadOnly && window.setReadOnly(true);", null)
                loadVersion()
            }
        }
        binding.webView.addJavascriptInterface(
            EditorBridge(
                onTitle = {},
                onEditorReady = { runOnUiThread { editorReady = true; maybeHideLoading() } }
            ),
            BRIDGE_NAME
        )
    }

    private fun loadVersion() {
        lifecycleScope.launch {
            runCatching { DocumentApi.getHistory(documentId, historyId) }
                .onSuccess { v ->
                    val author = v.createUser?.let { OwnerNames.load(this@HistoryPreviewActivity)[it] }
                    val meta = JSONObject().apply {
                        put("title", v.title.orEmpty().ifBlank { getString(R.string.common_untitled) })
                        if (!author.isNullOrBlank()) put("author", author)
                        put("updated", getString(R.string.history_saved_at, HistoryTime.absolute(this@HistoryPreviewActivity, v.createTime)))
                    }
                    evalJs("window.setDocumentMeta && window.setDocumentMeta('${jsEscape(meta.toString())}');")
                    v.blocks?.let { evalJs("window.setDocumentContent && window.setDocumentContent('${jsEscape(it)}');") }
                    contentLoaded = true
                    binding.btnRestore.isEnabled = true
                    maybeHideLoading()
                }
                .onFailure { e ->
                    Log.w(TAG, "버전 본문 조회 실패", e)
                    binding.progress.visibility = View.GONE
                    binding.tvLoading.text = getString(R.string.history_load_version_failed)
                }
        }
    }

    private fun maybeHideLoading() {
        if (editorReady && contentLoaded) binding.loading.visibility = View.GONE
    }

    private fun confirmRestore() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.history_restore_confirm_title))
            .setMessage(getString(R.string.history_restore_confirm_msg))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.history_restore)) { _, _ -> restore() }
            .show()
    }

    private fun restore() {
        binding.btnRestore.isEnabled = false
        LoadingIndicator.show(this)
        lifecycleScope.launch {
            runCatching { DocumentApi.restoreHistory(documentId, historyId) }
                .onSuccess {
                    LoadingIndicator.hide(this@HistoryPreviewActivity)
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_VERSION_NO, versionNo))
                    finish()
                }
                .onFailure { e ->
                    Log.w(TAG, "복원 실패", e)
                    LoadingIndicator.hide(this@HistoryPreviewActivity)
                    binding.btnRestore.isEnabled = true
                    Toast.makeText(this@HistoryPreviewActivity, getString(R.string.history_restore_failed), Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun evalJs(script: String) = binding.webView.evaluateJavascript(script, null)

    /** JS 작은따옴표 문자열 인자로 안전하게 넘기기 위한 이스케이프. */
    private fun jsEscape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace(" ", "\\u2028")
        .replace(" ", "\\u2029")

    companion object {
        private const val TAG = "HistoryPreview"
        private const val BRIDGE_NAME = "Android"
        private const val EXTRA_DOCUMENT_ID = "document_id"
        private const val EXTRA_HISTORY_ID = "history_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CREATE_TIME = "create_time"
        const val EXTRA_VERSION_NO = "version_no"

        fun intent(
            context: Context,
            documentId: String,
            historyId: String,
            versionNo: Int,
            title: String,
            createTime: String?
        ): Intent = Intent(context, HistoryPreviewActivity::class.java)
            .putExtra(EXTRA_DOCUMENT_ID, documentId)
            .putExtra(EXTRA_HISTORY_ID, historyId)
            .putExtra(EXTRA_VERSION_NO, versionNo)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_CREATE_TIME, createTime)
    }
}
