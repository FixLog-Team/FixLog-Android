package kr.co.fixlog.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
 * AI 검색 화면. 하단 입력창의 질문을 /ai/ask 로 보내 의미 기반 답변 + 참고 문서를 받아 표시한다.
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

    /** 하단 입력창의 검색 액션과 예시 질문 탭을 검색 실행에 연결한다. */
    private fun setupSearch() {
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(binding.etSearch.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        // 예시 질문 탭 → 입력창에 채우고 바로 검색.
        listOf(binding.ex1, binding.ex2, binding.ex3, binding.ex4).forEach { chip ->
            chip.setOnClickListener {
                val q = chip.text?.toString().orEmpty()
                binding.etSearch.setText(q)
                runSearch(q)
            }
        }
    }

    /** 질문을 /ai/ask 로 검색해 답변 + 참고 문서를 렌더링한다. 빈 질문은 무시. */
    private fun runSearch(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return
        if (isSearching) return
        isSearching = true
        hideKeyboard()

        binding.llEmpty.visibility = View.GONE
        binding.tvAnswer.visibility = View.GONE
        binding.llResults.removeAllViews()
        binding.pbLoading.visibility = View.VISIBLE

        lifecycleScope.launch {
            runCatching { AiApi.ask(query) }
                .onSuccess { resp ->
                    binding.pbLoading.visibility = View.GONE
                    binding.tvAnswer.text = resp.answer
                    binding.tvAnswer.visibility = View.VISIBLE
                    renderReferences(resp.references)
                }
                .onFailure {
                    binding.pbLoading.visibility = View.GONE
                    binding.tvAnswer.text = it.toAiMessage(this@SearchActivity)
                    binding.tvAnswer.visibility = View.VISIBLE
                }
            isSearching = false
        }
    }

    /** 참고 문서 카드를 목록에 채운다. 카드를 탭하면 해당 문서를 에디터로 연다. */
    private fun renderReferences(references: List<SearchResultDto>) {
        binding.llResults.removeAllViews()
        references.forEach { ref ->
            val card = layoutInflater.inflate(R.layout.item_search_result, binding.llResults, false)
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
            binding.llResults.addView(card)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }
}
