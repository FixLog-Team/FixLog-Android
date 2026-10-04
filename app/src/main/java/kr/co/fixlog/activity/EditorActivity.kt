package kr.co.fixlog.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.adapter.SlashCommandAdapter
import kr.co.fixlog.bridge.EditorBridge
import kr.co.fixlog.data.remote.AiApi
import kr.co.fixlog.data.remote.DocumentApi
import kr.co.fixlog.data.remote.dto.DocumentDto
import kr.co.fixlog.databinding.ActivityEditorBinding
import kr.co.fixlog.model.SlashCommand
import kr.co.fixlog.util.BottomNav
import kr.co.fixlog.util.DocumentActionsHelper
import kr.co.fixlog.util.toAiMessage
import kr.co.fixlog.util.launchWithLoading
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private var slashDialog: BottomSheetDialog? = null
    private var slashAdapter: SlashCommandAdapter? = null

    // 본문 헤더 제목 편집 → 앱바 제목 반영 시, 앱바 TextWatcher가 다시 헤더로 되돌려보내
    // 캐럿이 튀는 무한 반영을 막기 위한 가드.
    private var syncingTitleFromWeb = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupTitle()
        // 뒤로가기(툴바 버튼 + 시스템 back) 시 본문을 저장하고 종료한다.
        binding.btnBack.setOnClickListener { saveAndFinish() }
        onBackPressedDispatcher.addCallback(this) { saveAndFinish() }
        setupFavorite()
        setupHeaderActions()
        setupBottomNav()

        setupWebView()
        binding.webView.loadUrl("file:///android_asset/editor.html")
    }

    /**
     * 상단 제목(EditText) 설정.
     * - 기존 문서: 전달받은 제목을 채운다.
     * - 새 문서(file_id 없음): 제목을 비워 힌트를 노출하고, 제목 입력에 포커스+키보드를 띄워
     *   "제목 먼저 작성"이 가능하게 한다.
     * - 제목 입력이 바뀌면 WebView 헤더 제목도 실시간으로 동기화한다.
     */
    private fun setupTitle() {
        val name = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        binding.tvTitle.setText(name)

        // 제목(앱바) → WebView 헤더 실시간 반영.
        // 단, 변경이 헤더 편집에서 비롯된 경우(syncingTitleFromWeb)는 되돌려보내지 않는다(캐럿 튐/루프 방지).
        binding.tvTitle.doAfterTextChanged { editable ->
            if (syncingTitleFromWeb) return@doAfterTextChanged
            val t = editable?.toString().orEmpty()
            val safe = t.replace("\\", "\\\\").replace("'", "\\'")
            binding.webView.evaluateJavascript(
                "window.setDocumentTitle && window.setDocumentTitle('$safe');",
                null
            )
        }
        // 완료(Done) 시 키보드를 내리고 포커스를 해제한다.
        binding.tvTitle.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.tvTitle.clearFocus()
                hideKeyboard(binding.tvTitle)
                true
            } else {
                false
            }
        }

        // 새 문서면 제목부터 입력하도록 포커스 + 키보드.
        if (intent.getStringExtra(EXTRA_FILE_ID) == null) {
            binding.tvTitle.requestFocus()
            binding.tvTitle.post { showKeyboard(binding.tvTitle) }
        }
    }

    private fun showKeyboard(view: android.view.View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(view: android.view.View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /**
     * 즐겨찾기(☆) 토글. 서버(POST/DELETE /api/documents/{id}/favorite)와 동기화하며,
     * 로컬(SharedPreferences)은 즉시 반응/오프라인 폴백을 위한 미러로 유지한다.
     * 진입 시 서버 즐겨찾기 목록으로 상태를 보정한다.
     */
    private fun setupFavorite() {
        val fileId = intent.getStringExtra(EXTRA_FILE_ID) ?: return
        var state = isFavorite(fileId)
        renderFavorite(state)

        // 서버 상태로 보정(실패 시 로컬 미러 유지).
        lifecycleScope.launch {
            runCatching { DocumentApi.getFavorites() }
                .onSuccess { favs ->
                    val serverFav = favs.any { it.documentId == fileId }
                    if (serverFav != state) {
                        state = serverFav
                        setLocalFavorite(fileId, serverFav)
                        renderFavorite(serverFav)
                    }
                }
        }

        binding.btnFavorite.setOnClickListener {
            val next = !state
            state = next
            setLocalFavorite(fileId, next) // 즉시 반영
            renderFavorite(next)
            lifecycleScope.launch {
                runCatching {
                    if (next) DocumentApi.addFavorite(fileId) else DocumentApi.removeFavorite(fileId)
                }.onFailure { e ->
                    Log.w(TAG, "즐겨찾기 변경 실패", e)
                    state = !next // 롤백
                    setLocalFavorite(fileId, state)
                    renderFavorite(state)
                    Toast.makeText(this@EditorActivity, getString(R.string.editor_favorite_change_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setLocalFavorite(fileId: String, on: Boolean) {
        getSharedPreferences(PREF_FAVORITES, MODE_PRIVATE).edit().putBoolean(fileId, on).apply()
    }

    /**
     * 상단 우측 액션 버튼(별표 옆) 연결.
     * - AI(✦): 문서 요약 진입점 (요약 연동은 후속 — 현재는 진입 안내).
     * - 더보기(…): 문서 액션 메뉴(이름 변경/복제/이동/삭제) — 저장된 문서에서만.
     * (별표는 [setupFavorite]에서 별도 처리.)
     */
    private fun setupHeaderActions() {
        binding.btnAi.setOnClickListener { onAiClicked() }
        binding.btnMore.setOnClickListener { showMoreMenu() }
    }

    /**
     * AI(✦) → 현재 본문(에디터에 보이는 그대로)을 요약해 바텀시트로 보여준다.
     * 서버 DB의 저장본이 아니라 [getPlainText]로 읽은 현재 본문 평문을 POST /ai/summarize 로 직접 보낸다
     * → 화면 내용과 요약이 항상 일치. "이어서 대화" 시 이 본문+요약을 컨텍스트로 검색 화면에 넘긴다.
     */
    private fun onAiClicked() {
        val view = layoutInflater.inflate(R.layout.sheet_ai_summary, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)

        val loading = view.findViewById<android.view.View>(R.id.loading)
        val tvLoading = view.findViewById<TextView>(R.id.tv_loading)
        val tvSummary = view.findViewById<TextView>(R.id.tv_summary)
        val btnContinue = view.findViewById<android.view.View>(R.id.btn_continue)
        val btnSuggestLabels = view.findViewById<android.view.View>(R.id.btn_suggest_labels)
        view.findViewById<android.view.View>(R.id.btn_close).setOnClickListener { dialog.dismiss() }

        // 요약 완료(또는 실패) 전까지 "이어서 대화"/"라벨 추천"은 비활성.
        btnContinue.isEnabled = false
        btnSuggestLabels.isEnabled = false

        // 이어서 대화/라벨 추천에 넘길 상태(본문/요약).
        var bodyText = ""
        var summaryText = ""
        btnContinue.setOnClickListener {
            dialog.dismiss()
            openSearchWithContext(bodyText, summaryText)
        }
        btnSuggestLabels.setOnClickListener {
            dialog.dismiss()
            suggestLabels(bodyText)
        }

        loading.visibility = android.view.View.VISIBLE
        tvLoading.text = getString(R.string.editor_summarizing)
        dialog.show()

        // 현재 본문 평문을 읽어 요약한다.
        binding.webView.evaluateJavascript("window.getPlainText ? window.getPlainText() : ''") { raw ->
            val content = decodeJsString(raw).trim()
            bodyText = content
            if (content.isBlank()) {
                loading.visibility = android.view.View.GONE
                tvSummary.text = getString(R.string.editor_summary_empty)
                return@evaluateJavascript
            }
            lifecycleScope.launch {
                runCatching { AiApi.summarize(content) }
                    .onSuccess { summary ->
                        loading.visibility = android.view.View.GONE
                        summaryText = summary
                        tvSummary.text = summary
                        btnContinue.isEnabled = true
                        btnSuggestLabels.isEnabled = true
                    }
                    .onFailure { e ->
                        Log.w(TAG, "AI 요약 실패: ${e::class.simpleName} ${e.message}")
                        loading.visibility = android.view.View.GONE
                        tvSummary.text = e.toAiMessage(this@EditorActivity)
                        // 요약이 실패해도 본문 기반으로 이어서 대화/라벨 추천은 가능하게 한다.
                        btnContinue.isEnabled = true
                        btnSuggestLabels.isEnabled = true
                    }
            }
        }
    }

    /**
     * 라벨 수정/추가 버튼(에디터 헤더) → 라벨 편집 다이얼로그.
     * 저장되지 않은 새 문서면 먼저 저장해 id를 확보한 뒤 연다.
     */
    private fun onEditLabelsClicked() {
        val existing = intent.getStringExtra(EXTRA_FILE_ID)
        if (existing != null) {
            openLabelEditor(existing)
        } else {
            persistCurrent { savedId ->
                if (savedId == null) Toast.makeText(this, getString(R.string.editor_write_title_or_content_first), Toast.LENGTH_SHORT).show()
                else openLabelEditor(savedId)
            }
        }
    }

    private fun openLabelEditor(documentId: String) {
        kr.co.fixlog.util.LabelEditorHelper.show(this, lifecycleScope, documentId) {
            // 변경 후 헤더 라벨 새로고침.
            fetchAndInjectMeta()
        }
    }

    /**
     * AI 라벨 추천: 현재 본문으로 /ai/tags 호출 → 추천 태그를 다중 선택 → 선택한 태그를 라벨로 추가.
     * 저장되지 않은 새 문서면 먼저 저장해 id를 확보한다.
     */
    private fun suggestLabels(bodyText: String) {
        if (bodyText.isBlank()) {
            Toast.makeText(this, getString(R.string.editor_write_content_first), Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val tags = runCatching { AiApi.suggestTags(bodyText) }.getOrElse {
                Toast.makeText(this@EditorActivity, it.toAiMessage(this@EditorActivity), Toast.LENGTH_SHORT).show()
                emptyList()
            }
            if (tags.isEmpty()) {
                Toast.makeText(this@EditorActivity, getString(R.string.editor_no_suggested_labels), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val checked = BooleanArray(tags.size)
            AlertDialog.Builder(this@EditorActivity)
                .setTitle(getString(R.string.editor_suggested_labels))
                .setMultiChoiceItems(tags.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
                .setNegativeButton(getString(R.string.common_cancel), null)
                .setPositiveButton(getString(R.string.common_add)) { _, _ ->
                    val selected = tags.filterIndexed { i, _ -> checked[i] }
                    if (selected.isEmpty()) return@setPositiveButton
                    addLabels(selected)
                }
                .show()
        }
    }

    /** 선택한 라벨들을 문서에 추가(필요 시 먼저 저장) 후 헤더 새로고침. */
    private fun addLabels(labels: List<String>) {
        val existing = intent.getStringExtra(EXTRA_FILE_ID)
        if (existing != null) {
            doAddLabels(existing, labels)
        } else {
            persistCurrent { savedId ->
                if (savedId == null) Toast.makeText(this, getString(R.string.editor_save_document_first), Toast.LENGTH_SHORT).show()
                else doAddLabels(savedId, labels)
            }
        }
    }

    private fun doAddLabels(documentId: String, labels: List<String>) {
        lifecycleScope.launch {
            labels.forEach { runCatching { DocumentApi.addLabel(documentId, it) } }
            Toast.makeText(this@EditorActivity, getString(R.string.editor_labels_added), Toast.LENGTH_SHORT).show()
            fetchAndInjectMeta()
        }
    }

    /** 현재 문서(본문+요약)를 컨텍스트로 검색 화면을 열어 이어서 질문하게 한다. */
    private fun openSearchWithContext(body: String, summary: String) {
        val title = binding.tvTitle.text?.toString()?.trim().orEmpty()
        startActivity(Intent(this, SearchActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(SearchActivity.EXTRA_CONTEXT_TITLE, title)
            putExtra(SearchActivity.EXTRA_CONTEXT_BODY, body)
            putExtra(SearchActivity.EXTRA_CONTEXT_SUMMARY, summary)
        })
    }

    /** evaluateJavascript가 돌려주는 JSON 인코딩 문자열("...")을 실제 문자열로 디코드. */
    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull() ?: raw
    }

    /** 더보기(…) → 문서 액션 메뉴. 새 문서(id 없음)는 저장 전이라 안내만 한다. */
    private fun showMoreMenu() {
        val id = intent.getStringExtra(EXTRA_FILE_ID)
        if (id == null) {
            Toast.makeText(this, getString(R.string.editor_available_after_save), Toast.LENGTH_SHORT).show()
            return
        }
        val title = binding.tvTitle.text?.toString()?.ifBlank { "Untitled" } ?: "Untitled"
        DocumentActionsHelper.show(this, lifecycleScope, id, title, onChanged = { fetchAndInjectMeta() })
    }

    private fun isFavorite(fileId: String): Boolean =
        getSharedPreferences(PREF_FAVORITES, MODE_PRIVATE).getBoolean(fileId, false)

    private fun renderFavorite(on: Boolean) {
        binding.btnFavorite.setImageResource(
            if (on) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )
    }

    private fun setupWebView() {
        // 원격 WebView 디버깅(chrome://inspect)은 디버그 빌드에서만 허용. 운영 빌드에선
        // 문서 본문 등 WebView 내부가 외부 도구로 노출되지 않도록 끈다.
        android.webkit.WebView.setWebContentsDebuggingEnabled(kr.co.fixlog.BuildConfig.DEBUG)
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 앱 로케일을 에디터(WebView)에 전달해 슬래시 메뉴/플레이스홀더 등을 해당 언어로 표시.
                val lang = resources.configuration.locales[0].language
                view?.evaluateJavascript("window.setLang && window.setLang('$lang');", null)
                // 우선 상단바 제목을 본문 헤더에 즉시 주입(네트워크와 무관하게 항상 보이게).
                val title = binding.tvTitle.text?.toString().orEmpty()
                val safe = title.replace("\\", "\\\\").replace("'", "\\'")
                view?.evaluateJavascript(
                    "window.setDocumentTitle && window.setDocumentTitle('$safe');",
                    null
                )
                // 이어서 서버에서 실제 메타(작성자/수정일)를 가져와 헤더에 채운다(best-effort).
                fetchAndInjectMeta()
            }
        }
        binding.webView.addJavascriptInterface(
            EditorBridge(
                onSlash = { json -> runOnUiThread { handleSlashState(json) } },
                onTitle = { title -> runOnUiThread { applyTitleFromWeb(title) } },
                onEditLabels = { runOnUiThread { onEditLabelsClicked() } }
            ),
            BRIDGE_NAME
        )
    }

    /**
     * 본문 헤더(#doc-title) 편집 결과를 앱바 제목에 반영한다.
     * 이 경로에서는 앱바 → 헤더 재반영을 막아(syncingTitleFromWeb) 캐럿 튐/무한 루프를 방지한다.
     * 저장은 앱바 제목을 읽으므로, 이 동기화만으로 본문 제목 편집분이 저장에 반영된다.
     */
    private fun applyTitleFromWeb(title: String) {
        if ((binding.tvTitle.text?.toString() ?: "") == title) return
        syncingTitleFromWeb = true
        binding.tvTitle.setText(title)
        syncingTitleFromWeb = false
    }

    /** 하단 네비게이션 배선. 에디터는 "문서" 탭의 하위 상세 화면이다. */
    private fun setupBottomNav() {
        // 에디터는 문서 탭의 하위 상세 화면이므로 "문서" 탭을 활성 상태로 표시한다.
        BottomNav.setup(this, binding.bottomNav, BottomNav.Tab.DOCS)
    }

    /**
     * 서버에서 문서 메타를 조회해 WebView 헤더(작성자/수정일/제목)에 주입한다.
     * 실패(비로그인/문서없음/네트워크)해도 화면은 정상 — 제목/플레이스홀더가 유지된다.
     */
    private fun fetchAndInjectMeta() {
        val fileId = intent.getStringExtra(EXTRA_FILE_ID) ?: return
        launchWithLoading {
            runCatching { DocumentApi.getDocument(fileId) }
                .onSuccess { dto ->
                    // 라벨은 별도 엔드포인트라 함께 조회해 헤더에 표시(실패해도 본문/메타는 정상).
                    val labels = runCatching { DocumentApi.getLabels(fileId) }.getOrDefault(emptyList())
                    injectMeta(dto, labels)
                    injectContent(dto)
                }
                .onFailure { e -> Log.d(TAG, "문서 조회 실패(무시): ${e.message}") }
        }
    }

    /** 서버 본문(blocks JSON 문자열)을 WebView 에디터에 주입한다. blocks가 없으면 빈 문서 유지. */
    private fun injectContent(dto: DocumentDto) {
        val blocks = dto.blocks ?: return
        val arg = jsEscape(blocks)
        binding.webView.evaluateJavascript(
            "window.setDocumentContent && window.setDocumentContent('$arg');",
            null
        )
    }

    private fun injectMeta(dto: DocumentDto, labels: List<String> = emptyList()) {
        // 소유자(작성자) 이름 우선: 상세 응답의 createUserName → 없으면 식별자 폴백.
        val author = dto.createUserName ?: dto.createUser ?: dto.updateUser
        val updatedRaw = dto.updateTime ?: dto.createTime
        val meta = JSONObject().apply {
            put("title", dto.title)
            if (!author.isNullOrBlank()) put("author", author)
            formatUpdated(updatedRaw)?.let { put("updated", it) }
            if (labels.isNotEmpty()) put("tags", org.json.JSONArray(labels))
        }
        // JSON 문자열을 JS 문자열 인자로 안전하게 전달(작은따옴표/역슬래시 이스케이프).
        val arg = meta.toString().replace("\\", "\\\\").replace("'", "\\'")
        binding.webView.evaluateJavascript(
            "window.setDocumentMeta && window.setDocumentMeta('$arg');",
            null
        )
    }

    /** ISO-8601(예: 2026-05-19T12:34:56.789Z) → "2026년 5월 19일 수정". 파싱 실패 시 null. */
    private fun formatUpdated(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val dt = OffsetDateTime.parse(raw)
            val formatted = dt.format(DateTimeFormatter.ofPattern(getString(R.string.editor_date_pattern), Locale.KOREAN))
            getString(R.string.editor_updated, formatted)
        }.getOrNull()
    }

    private fun handleSlashState(json: String) {
        val state = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "Bad slash menu payload: $json", e)
            return
        }

        if (!state.optBoolean("show", false)) {
            hideSlashMenu()
            return
        }

        val itemsArr = state.optJSONArray("items")
        val items = mutableListOf<SlashCommand>()
        if (itemsArr != null) {
            for (i in 0 until itemsArr.length()) {
                val it = itemsArr.optJSONObject(i) ?: continue
                items.add(
                    SlashCommand(
                        id = it.optString("id"),
                        title = it.optString("title"),
                        subtitle = it.optString("subtitle", "")
                    )
                )
            }
        }
        showSlashMenu(items)
    }

    private fun showSlashMenu(items: List<SlashCommand>) {
        if (slashDialog == null) {
            val view = LayoutInflater.from(this)
                .inflate(R.layout.bottom_sheet_slash_menu, null)
            val rv = view.findViewById<RecyclerView>(R.id.rv_commands)
            slashAdapter = SlashCommandAdapter { cmd ->
                executeSlashCommand(cmd.id)
                hideSlashMenu()
            }
            rv.layoutManager = LinearLayoutManager(this)
            rv.adapter = slashAdapter

            slashDialog = BottomSheetDialog(this).apply {
                setContentView(view)
                // Don't steal focus from the WebView so the user can keep typing
                // to filter commands while the sheet is visible.
                window?.setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
                setOnDismissListener {
                    binding.webView.evaluateJavascript(
                        "window.clearSlashText && window.clearSlashText();",
                        null
                    )
                    slashDialog = null
                    slashAdapter = null
                }
            }
        }
        slashAdapter?.setItems(items)
        if (slashDialog?.isShowing != true) slashDialog?.show()
    }

    private fun hideSlashMenu() {
        slashDialog?.dismiss()
    }

    private fun executeSlashCommand(id: String) {
        val safeId = id.replace("\\", "\\\\").replace("'", "\\'")
        binding.webView.evaluateJavascript(
            "window.executeSlashCommand && window.executeSlashCommand('$safeId');",
            null
        )
    }

    /**
     * 현재 제목/본문을 서버에 저장한 뒤 화면을 종료한다.
     * - 기존 문서(id 있음): 제목+본문을 저장(PUT). 에디터 미준비/본문 없음이면 저장을 건너뛴다(본문 유실 방지).
     * - 새 문서(id 없음): 제목이나 본문 중 하나라도 있으면 문서를 새로 생성(POST)하고, 본문이 있으면 이어서 저장.
     *   둘 다 비어 있으면 저장 없이 종료.
     * - 저장 성공/실패와 무관하게 항상 종료한다(실패는 toast로만 안내).
     */
    private fun saveAndFinish() {
        persistCurrent { finish() }
    }

    /**
     * 현재 제목/본문을 서버에 저장하고, 저장된 문서 id를 콜백으로 돌려준다(화면을 종료하지 않음).
     * - 기존 문서(id 있음): 본문이 있으면 제목+본문 저장(PUT). 본문이 없으면 유실 방지로 저장 스킵하되 id는 그대로 반환.
     * - 새 문서(id 없음): 제목/본문 중 하나라도 있으면 생성(POST) 후 본문 저장. 새 id를 intent에 반영해
     *   이후 저장/더보기/AI가 같은 문서를 가리키게 한다. 둘 다 비어 있으면 null.
     * - 실패 시 toast로 안내하고 null(또는 기존 id)을 반환한다.
     */
    private fun persistCurrent(onSaved: (String?) -> Unit) {
        val existingId = intent.getStringExtra(EXTRA_FILE_ID)
        val rawTitle = binding.tvTitle.text?.toString()?.trim().orEmpty()
        // getDocumentContent()는 BlockNote 블록 배열을 반환 → evaluateJavascript가 JSON 텍스트로 넘겨준다.
        binding.webView.evaluateJavascript(
            "window.getDocumentContent ? window.getDocumentContent() : null"
        ) { value ->
            val blocksJson = value?.takeIf { it.isNotBlank() && it != "null" }
            val title = rawTitle.ifBlank { "Untitled" }
            lifecycleScope.launch {
                val savedId = runCatching {
                    when {
                        // 기존 문서
                        existingId != null -> {
                            if (blocksJson != null) DocumentApi.saveContent(existingId, title, blocksJson)
                            existingId
                        }
                        // 새 문서인데 제목/본문 모두 없음 → 생성 안 함
                        rawTitle.isBlank() && blocksJson == null -> null
                        // 새 문서 생성
                        else -> {
                            val created = DocumentApi.create(folderId = null, title = title)
                            if (blocksJson != null) DocumentApi.saveContent(created.documentId, title, blocksJson)
                            intent.putExtra(EXTRA_FILE_ID, created.documentId)
                            created.documentId
                        }
                    }
                }.onFailure { e ->
                    Log.w(TAG, "저장 실패: ${e.message}")
                    Toast.makeText(this@EditorActivity, getString(R.string.editor_save_failed), Toast.LENGTH_SHORT).show()
                }.getOrDefault(existingId)
                onSaved(savedId)
            }
        }
    }

    /** JSON 문자열을 작은따옴표 JS 문자열 인자로 안전하게 전달하기 위한 이스케이프. */
    private fun jsEscape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    override fun onDestroy() {
        binding.webView.removeJavascriptInterface(BRIDGE_NAME)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FILE_ID = "file_id"
        const val EXTRA_FILE_NAME = "file_name"
        private const val BRIDGE_NAME = "Android"
        private const val TAG = "EditorActivity"
        private const val PREF_FAVORITES = "fixlog_favorites"
    }
}
