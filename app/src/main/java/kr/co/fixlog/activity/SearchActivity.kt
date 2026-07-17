package kr.co.fixlog.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.data.remote.AiApi
import kr.co.fixlog.data.remote.dto.SearchResultDto
import kr.co.fixlog.databinding.ActivitySearchBinding
import kr.co.fixlog.util.AllDialog
import kr.co.fixlog.util.BottomNav
import kr.co.fixlog.util.toAiMessage

/**
 * AI 검색 화면. 하단 입력창(또는 전송 버튼)의 질문을 /ai/ask 로 보내 의미 기반 답변 + 참고 문서를 받는다.
 * 채팅 형식: 내가 보낸 질문은 오른쪽 버블, 그 아래에 AI 답변 + 참고 문서 카드를 표시한다.
 * 참고 문서 카드를 탭하면 해당 문서를 에디터로 연다.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding

    /** 검색 진행 중 여부. 한 번의 검색 액션이 리스너로 중복 전달돼 요청이 두 번 나가는 것을 막는다. */
    private var isSearching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            // 시스템 바 + 키보드(IME) 인셋을 함께 패딩으로 반영한다.
            // 키보드가 올라오면 하단 패딩이 늘어나 하단 고정 검색창이 키보드 위로 밀려 올라간다.
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setupSearch()
        BottomNav.setup(this, binding.bottomNav, BottomNav.Tab.SEARCH) { AllDialog.show(this) }
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
        // 예시 질문 탭 → 바로 검색(입력창엔 남기지 않는다).
        listOf(binding.ex1, binding.ex2, binding.ex3, binding.ex4).forEach { chip ->
            chip.setOnClickListener { runSearch(chip.text?.toString().orEmpty()) }
        }
    }

    /** 질문을 /ai/ask 로 검색해 채팅(질문 버블 + 답변)으로 표시한다. 빈 질문은 무시. */
    private fun runSearch(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return
        if (isSearching) return
        isSearching = true
        hideKeyboard()

        // 질문을 채팅에 추가하고 입력창을 비운다.
        binding.llEmpty.visibility = View.GONE
        addQuestionBubble(query)
        binding.etSearch.setText("")

        binding.pbLoading.visibility = View.VISIBLE
        scrollToBottom()

        lifecycleScope.launch {
            runCatching { AiApi.ask(query) }
                .onSuccess { resp ->
                    binding.pbLoading.visibility = View.GONE
                    addAnswerBubble(resp.answer, resp.references)
                }
                .onFailure {
                    binding.pbLoading.visibility = View.GONE
                    addAnswerBubble(it.toAiMessage(this@SearchActivity), emptyList())
                }
            scrollToBottom()
            isSearching = false
        }
    }

    /** 오른쪽 정렬 질문 버블을 채팅에 추가한다. */
    private fun addQuestionBubble(question: String) {
        val view = layoutInflater.inflate(R.layout.item_chat_question, binding.llChat, false)
        view.findViewById<TextView>(R.id.tv_chat_question).text = question
        binding.llChat.addView(view)
    }

    /** 왼쪽 정렬 답변 버블 + 참고 문서 카드를 채팅에 추가한다. */
    private fun addAnswerBubble(answer: String, references: List<SearchResultDto>) {
        val view = layoutInflater.inflate(R.layout.item_chat_answer, binding.llChat, false)
        view.findViewById<TextView>(R.id.tv_chat_answer).text = answer
        val refsContainer = view.findViewById<LinearLayout>(R.id.ll_chat_refs)
        renderReferences(refsContainer, references)
        binding.llChat.addView(view)
    }

    /** 참고 문서 카드를 지정 컨테이너에 채운다. 카드를 탭하면 해당 문서를 에디터로 연다. */
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

    /** 새 메시지가 추가되면 채팅 맨 아래로 스크롤한다. */
    private fun scrollToBottom() {
        binding.svChat.post { binding.svChat.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }
}
