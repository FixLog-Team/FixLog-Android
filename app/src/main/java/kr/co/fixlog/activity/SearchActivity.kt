package kr.co.fixlog.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.data.remote.ConversationApi
import kr.co.fixlog.data.remote.dto.ConversationDto
import kr.co.fixlog.data.remote.dto.MessageDto
import kr.co.fixlog.data.remote.dto.SearchResultDto
import kr.co.fixlog.databinding.ActivitySearchBinding
import kr.co.fixlog.util.BottomNav
import kr.co.fixlog.util.ConversationManager
import kr.co.fixlog.util.Markdown
import kr.co.fixlog.util.toAiMessage

/**
 * AI 검색(대화방) 화면. 멀티턴 대화방 기반으로 질문/답변을 주고받는다.
 *
 * - 상단 우측 채팅방 버튼: 대화방 목록 팝업 → 선택 시 전환, "새 대화"로 새 대화방 시작.
 * - 앱 시작/탭 복귀 시: 마지막으로 접속한 대화방을 복원해 표시한다([ConversationManager]).
 * - 첫 질문 시 대화방을 생성(POST /conversations)하고, 이후 같은 대화방에 메시지를 전송한다.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding

    /** 현재 열려 있는 대화방 id. null이면 아직 대화방이 없음(첫 질문 시 생성). */
    private var currentConversationId: String? = null

    /** 요청 중복 방지. */
    private var isSending = false

    /** 에디터 "이어서 대화"로 전달된 문서 컨텍스트. 첫 질문에 함께 실어 보낸 뒤 비운다. */
    private var pendingContext: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupSearch()
        binding.btnConversations.setOnClickListener { showConversationsDialog() }
        binding.btnDeleteChat.setOnClickListener { confirmDeleteCurrentConversation() }
        BottomNav.setup(this, binding.bottomNav, BottomNav.Tab.SEARCH)

        handleIncomingContext(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingContext(intent)
    }

    override fun onResume() {
        super.onResume()
        // 컨텍스트로 새 대화를 막 시작한 경우엔 마지막 대화방을 복원하지 않는다.
        if (currentConversationId == null && pendingContext == null) {
            val last = ConversationManager.getLast(this)
            if (last != null) switchToConversation(last)
        }
    }

    /**
     * 에디터 "이어서 대화"로 넘어온 문서 컨텍스트를 받아 새 대화를 준비한다.
     * 실제 컨텍스트는 사용자의 첫 질문과 함께 전송된다([runSearch]).
     */
    private fun handleIncomingContext(intent: Intent) {
        val body = intent.getStringExtra(EXTRA_CONTEXT_BODY)?.takeIf { it.isNotBlank() } ?: return
        // 재진입/회전 시 중복 처리 방지를 위해 소비 후 제거.
        intent.removeExtra(EXTRA_CONTEXT_BODY)
        val title = intent.getStringExtra(EXTRA_CONTEXT_TITLE).orEmpty()
        val summary = intent.getStringExtra(EXTRA_CONTEXT_SUMMARY).orEmpty()

        pendingContext = buildContext(title, body, summary)
        // 새 대화로 초기화(첫 질문 시 대화방 생성).
        currentConversationId = null
        binding.llChat.removeAllViews()
        binding.llEmpty.visibility = View.GONE
        // 요약을 위해 사용한(문서 컨텍스트) 질문은 표시하지 않고, 요약 결과를 첫 메시지로 보여준다.
        val opening = summary.takeIf { it.isNotBlank() }
            ?: getString(R.string.search_context_opening, title.ifBlank { getString(R.string.search_document) })
        addAnswerBubble(opening, emptyList())
        binding.etSearch.requestFocus()
    }

    private fun buildContext(title: String, body: String, summary: String): String = buildString {
        if (title.isNotBlank()) append("제목: ").append(title).append("\n\n")
        append("[문서 본문]\n").append(body)
        if (summary.isNotBlank()) append("\n\n[문서 요약]\n").append(summary)
    }

    /**
     * 표시용 질문 텍스트. 문서 컨텍스트가 실린 첫 질문(에디터 "이어서 대화")은 전체 컨텍스트 대신
     * 실제 질문([질문] 이후)만 보여준다. (이력 재로딩 시 거대한 컨텍스트가 질문으로 노출되는 것 방지)
     */
    private fun displayQuestion(content: String): String {
        val marker = "$QUESTION_MARKER\n"
        val idx = content.lastIndexOf(marker)
        return if (idx >= 0) content.substring(idx + marker.length).trim() else content
    }

    /** 입력창의 검색 액션 / 전송 버튼 / 예시 질문 탭을 검색 실행에 연결한다. */
    private fun setupSearch() {
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(binding.etSearch.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        binding.btnSend.setOnClickListener {
            runSearch(binding.etSearch.text?.toString().orEmpty())
        }
        listOf(binding.ex1, binding.ex2, binding.ex3, binding.ex4).forEach { chip ->
            chip.setOnClickListener { runSearch(chip.text?.toString().orEmpty()) }
        }
    }

    /** 질문 전송: 대화방이 없으면 생성 후, 있으면 그대로 메시지를 보낸다. */
    private fun runSearch(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty() || isSending) return
        isSending = true
        hideKeyboard()

        binding.llEmpty.visibility = View.GONE
        addQuestionBubble(query)
        binding.etSearch.setText("")
        binding.pbLoading.visibility = View.VISIBLE
        scrollToBottom()

        // 문서 컨텍스트가 있으면 첫 질문에 함께 실어 보낸다(화면에는 질문만 표시).
        val context = pendingContext
        pendingContext = null
        val toSend = if (context != null) {
            "다음 문서를 참고해서 답변해줘.\n\n$context\n\n$QUESTION_MARKER\n$query"
        } else {
            query
        }

        lifecycleScope.launch {
            runCatching {
                val convId = currentConversationId ?: ConversationApi.create(query.take(30)).also {
                    currentConversationId = it.conversationId
                    ConversationManager.setLast(this@SearchActivity, it.conversationId)
                }.conversationId
                ConversationApi.sendMessage(convId, toSend)
            }
                .onSuccess { msg ->
                    binding.pbLoading.visibility = View.GONE
                    addAnswerBubble(msg.content.orEmpty().ifBlank { getString(R.string.search_empty_response) }, msg.references.orEmpty())
                }
                .onFailure {
                    binding.pbLoading.visibility = View.GONE
                    addAnswerBubble(it.toAiMessage(this@SearchActivity), emptyList())
                }
            scrollToBottom()
            isSending = false
        }
    }

    // ---------------------------------------------------------------------
    // 대화방 목록 / 전환
    // ---------------------------------------------------------------------

    /** 채팅방 목록 팝업. "새 대화" + 기존 대화방 목록(각 항목 우측 X로 삭제). 선택 시 전환. */
    private fun showConversationsDialog() {
        lifecycleScope.launch {
            val conversations = runCatching { ConversationApi.list() }.getOrElse { e ->
                Log.w(TAG, "대화방 목록 조회 실패", e)
                toast(getString(R.string.search_load_conversations_failed))
                emptyList()
            }

            val container = android.widget.LinearLayout(this@SearchActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            val scroll = android.widget.ScrollView(this@SearchActivity).apply { addView(container) }
            val dialog = AlertDialog.Builder(this@SearchActivity)
                .setTitle(getString(R.string.search_conversations))
                .setView(scroll)
                .setNegativeButton(getString(R.string.common_close), null)
                .create()

            // ＋ 새 대화
            val newRow = layoutInflater.inflate(R.layout.item_conversation_row, container, false)
            newRow.findViewById<TextView>(R.id.tv_conv_title).text = getString(R.string.search_new_conversation_plus)
            newRow.findViewById<View>(R.id.btn_conv_delete).visibility = View.GONE
            newRow.findViewById<TextView>(R.id.tv_conv_title).setOnClickListener {
                startNewConversation(); dialog.dismiss()
            }
            container.addView(newRow)

            conversations.forEach { conv ->
                val row = layoutInflater.inflate(R.layout.item_conversation_row, container, false)
                row.findViewById<TextView>(R.id.tv_conv_title).apply {
                    text = conv.displayTitle
                    setOnClickListener { switchToConversation(conv.conversationId, conv); dialog.dismiss() }
                }
                row.findViewById<View>(R.id.btn_conv_delete).setOnClickListener {
                    confirmDeleteConversation(conv.conversationId) { dialog.dismiss() }
                }
                container.addView(row)
            }

            dialog.show()
        }
    }

    /** 상단 "현재 대화 삭제" 버튼: 지금 보고 있는 대화방을 삭제한다. */
    private fun confirmDeleteCurrentConversation() {
        val id = currentConversationId
        if (id == null) {
            toast(getString(R.string.search_no_conversation_to_delete))
            return
        }
        confirmDeleteConversation(id, onDeleted = null)
    }

    /** 대화방 삭제 확인 → 삭제 후 새 대화로 초기화. */
    private fun confirmDeleteConversation(conversationId: String, onDeleted: (() -> Unit)?) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.search_delete_conversation_title))
            .setMessage(getString(R.string.search_delete_conversation_message))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_delete)) { _, _ ->
                lifecycleScope.launch {
                    runCatching { ConversationApi.delete(conversationId) }
                        .onSuccess {
                            toast(getString(R.string.search_conversation_deleted))
                            onDeleted?.invoke()
                            // 삭제한 방이 현재 보던 방이면 새 대화로 초기화.
                            if (currentConversationId == conversationId) {
                                ConversationManager.clearLast(this@SearchActivity)
                                startNewConversation()
                            }
                        }
                        .onFailure { e ->
                            Log.w(TAG, "대화방 삭제 실패", e)
                            toast(getString(R.string.search_delete_conversation_failed))
                        }
                }
            }
            .show()
    }

    /** 새 대화 시작: 대화 로그를 비우고 첫 질문 시 대화방을 새로 생성하도록 한다. */
    private fun startNewConversation() {
        currentConversationId = null
        binding.llChat.removeAllViews()
        binding.llEmpty.visibility = View.VISIBLE
        toast(getString(R.string.search_new_conversation_started))
    }

    /** 지정 대화방으로 전환: 로그를 비우고 이력을 불러와 렌더한다. */
    private fun switchToConversation(conversationId: String, meta: ConversationDto? = null) {
        currentConversationId = conversationId
        ConversationManager.setLast(this, conversationId)
        binding.llChat.removeAllViews()
        binding.llEmpty.visibility = View.GONE
        binding.pbLoading.visibility = View.VISIBLE

        lifecycleScope.launch {
            runCatching { ConversationApi.messages(conversationId) }
                .onSuccess { messages ->
                    binding.pbLoading.visibility = View.GONE
                    renderHistory(messages)
                }
                .onFailure { e ->
                    binding.pbLoading.visibility = View.GONE
                    Log.w(TAG, "대화 이력 조회 실패", e)
                    // 이력이 없거나 실패해도 빈 대화방으로 진입은 유지.
                    if (binding.llChat.childCount == 0) binding.llEmpty.visibility = View.VISIBLE
                }
            scrollToBottom()
        }
    }

    /** 이력 메시지를 순서대로 질문/답변 버블로 렌더한다. */
    private fun renderHistory(messages: List<MessageDto>) {
        binding.llChat.removeAllViews()
        if (messages.isEmpty()) {
            binding.llEmpty.visibility = View.VISIBLE
            return
        }
        binding.llEmpty.visibility = View.GONE
        messages.forEach { m ->
            if (m.isUser) addQuestionBubble(displayQuestion(m.content.orEmpty()))
            else addAnswerBubble(m.content.orEmpty(), m.references.orEmpty())
        }
    }

    // ---------------------------------------------------------------------
    // 버블 렌더링
    // ---------------------------------------------------------------------

    private fun addQuestionBubble(question: String) {
        val view = layoutInflater.inflate(R.layout.item_chat_question, binding.llChat, false)
        view.findViewById<TextView>(R.id.tv_chat_question).text = question
        binding.llChat.addView(view)
    }

    private fun addAnswerBubble(answer: String, references: List<SearchResultDto>) {
        val view = layoutInflater.inflate(R.layout.item_chat_answer, binding.llChat, false)
        // 마크다운(굵게/기울임/코드/목록 등)을 스타일 적용해 표시.
        view.findViewById<TextView>(R.id.tv_chat_answer).text = Markdown.render(answer)
        val refsContainer = view.findViewById<LinearLayout>(R.id.ll_chat_refs)
        renderReferences(refsContainer, references)
        binding.llChat.addView(view)
    }

    private fun renderReferences(container: LinearLayout, references: List<SearchResultDto>) {
        container.removeAllViews()
        container.visibility = if (references.isEmpty()) View.GONE else View.VISIBLE
        references.forEach { ref ->
            val card = layoutInflater.inflate(R.layout.item_search_result, container, false)
            card.findViewById<TextView>(R.id.tv_result_title).text =
                ref.title?.ifBlank { getString(R.string.untitled_document) } ?: getString(R.string.untitled_document)
            val excerptView = card.findViewById<TextView>(R.id.tv_result_excerpt)
            val excerpt = ref.excerpt?.trim().orEmpty()
            if (excerpt.isEmpty()) {
                excerptView.visibility = View.GONE
            } else {
                excerptView.visibility = View.VISIBLE
                excerptView.text = excerpt
            }
            card.setOnClickListener {
                startActivity(Intent(this, EditorActivity::class.java).apply {
                    putExtra(EditorActivity.EXTRA_FILE_ID, ref.documentId)
                    putExtra(EditorActivity.EXTRA_FILE_NAME, ref.title.orEmpty())
                })
            }
            container.addView(card)
        }
    }

    private fun scrollToBottom() {
        binding.svChat.post { binding.svChat.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "SearchActivity"

        /** 문서 컨텍스트가 실린 첫 질문에서 실제 질문 구간을 구분하는 마커. */
        private const val QUESTION_MARKER = "[질문]"

        /** 에디터 "이어서 대화"에서 넘기는 문서 컨텍스트 익스트라. */
        const val EXTRA_CONTEXT_TITLE = "context_title"
        const val EXTRA_CONTEXT_BODY = "context_body"
        const val EXTRA_CONTEXT_SUMMARY = "context_summary"
    }
}
