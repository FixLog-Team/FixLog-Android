package kr.co.fixlog.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.adapter.HistoryAdapter
import kr.co.fixlog.adapter.HistoryAdapter.Row
import kr.co.fixlog.data.remote.DocumentApi
import kr.co.fixlog.databinding.ActivityDocumentHistoryBinding
import kr.co.fixlog.util.HistoryTime
import kr.co.fixlog.util.OwnerNames
import kr.co.fixlog.util.CoachMark

/**
 * 문서 버전 기록(웹 DocumentHistorySidePanel 대응). 문서 페이지의 더보기 → "버전 기록"에서 진입한다.
 *  - 목록: GET /api/documents/{id}/history (최신순) + 현재 문서 메타(GET /api/documents/{id})
 *  - 버전 번호는 가장 오래된 버전이 v1 (웹과 동일: versionNo = 개수 - index)
 *  - 제목이 바로 다음(더 최신) 버전과 다르면 "제목 변경" 배지
 *  - 현재 버전 → 에디터 열기, 이전 버전 → 읽기 전용 미리보기([HistoryPreviewActivity]) → 복원
 */
class DocumentHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentHistoryBinding
    private lateinit var adapter: HistoryAdapter
    private lateinit var documentId: String
    private var docTitle: String = ""

    /** 미리보기에서 복원하고 돌아오면 목록을 다시 불러오고 안내한다. */
    private val previewLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val versionNo = result.data?.getIntExtra(HistoryPreviewActivity.EXTRA_VERSION_NO, 0) ?: 0
        Toast.makeText(this, getString(R.string.history_restored, versionNo), Toast.LENGTH_SHORT).show()
        load(showProgress = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDocumentHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        documentId = intent.getStringExtra(EXTRA_DOCUMENT_ID) ?: run { finish(); return }
        docTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.tvDocTitle.text = docTitle.ifBlank { getString(R.string.common_untitled) }
        binding.btnBack.setOnClickListener { finish() }

        adapter = HistoryAdapter { row -> onRowClick(row) }
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = adapter

        binding.swipeRefresh.apply {
            setColorSchemeResources(R.color.editor_blue)
            setProgressBackgroundColorSchemeResource(R.color.surface)
            setOnRefreshListener { load(showProgress = false) }
        }

        load(showProgress = true)
        CoachMark.show(this, "history", CoachMark.Step(binding.swipeRefresh, R.string.coach_history_list))
    }

    private fun load(showProgress: Boolean) {
        if (showProgress) binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val metaJob = async { runCatching { DocumentApi.getMeta(documentId) }.getOrNull() }
            val historyJob = async { runCatching { DocumentApi.listHistory(documentId) } }
            val namesJob = async { OwnerNames.load(this@DocumentHistoryActivity) }
            val meta = metaJob.await()
            val history = historyJob.await()
            val names = namesJob.await()

            binding.progress.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false

            meta?.title?.let {
                docTitle = it
                binding.tvDocTitle.text = it.ifBlank { getString(R.string.common_untitled) }
            }
            val untitled = getString(R.string.common_untitled)
            fun metaLine(time: String?, userId: String?): String {
                val who = userId?.let { names[it] }
                return listOfNotNull(HistoryTime.relative(this@DocumentHistoryActivity, time), who).joinToString(" · ")
            }

            val rows = mutableListOf<Row>()
            rows += Row.Section(getString(R.string.history_current_section))
            rows += Row.Version(
                historyId = null,
                versionNo = 0,
                chip = getString(R.string.history_current_chip),
                title = docTitle.ifBlank { untitled },
                meta = metaLine(meta?.updateTime ?: meta?.createTime, meta?.updateUser ?: meta?.createUser),
                badge = null,
                createTime = meta?.updateTime
            )
            rows += Row.Section(getString(R.string.history_previous_section))

            history
                .onSuccess { page ->
                    val items = page.items
                    if (items.isEmpty()) rows += Row.Section(getString(R.string.history_empty))
                    items.forEachIndexed { index, h ->
                        // 바로 다음(더 최신) 버전, 최신 항목이면 현재 문서와 제목 비교.
                        val newerTitle = if (index == 0) docTitle else items[index - 1].title.orEmpty()
                        val versionNo = items.size - index
                        rows += Row.Version(
                            historyId = h.historyId,
                            versionNo = versionNo,
                            chip = "v$versionNo",
                            title = h.title?.ifBlank { null } ?: untitled,
                            meta = metaLine(h.createTime, h.createUser),
                            badge = if (h.title.orEmpty() != newerTitle) getString(R.string.history_title_changed) else null,
                            createTime = h.createTime
                        )
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "버전 기록 조회 실패", e)
                    rows += Row.Section(getString(R.string.history_load_failed))
                }
            adapter.submit(rows)
        }
    }

    private fun onRowClick(row: Row.Version) {
        val historyId = row.historyId
        if (historyId == null) {
            // 현재 버전 → 에디터로 열기
            startActivity(Intent(this, EditorActivity::class.java).apply {
                putExtra(EditorActivity.EXTRA_FILE_ID, documentId)
                putExtra(EditorActivity.EXTRA_FILE_NAME, docTitle)
            })
            return
        }
        previewLauncher.launch(
            HistoryPreviewActivity.intent(this, documentId, historyId, row.versionNo, row.title, row.createTime)
        )
    }

    companion object {
        private const val TAG = "DocumentHistory"
        private const val EXTRA_DOCUMENT_ID = "document_id"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, documentId: String, title: String): Intent =
            Intent(context, DocumentHistoryActivity::class.java)
                .putExtra(EXTRA_DOCUMENT_ID, documentId)
                .putExtra(EXTRA_TITLE, title)
    }
}
