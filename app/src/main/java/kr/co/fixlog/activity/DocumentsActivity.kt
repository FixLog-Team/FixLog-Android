package kr.co.fixlog.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.adapter.DocumentAdapter
import kr.co.fixlog.data.remote.DocumentApi
import kr.co.fixlog.data.remote.dto.DocumentDto
import kr.co.fixlog.databinding.ActivityDocumentsBinding
import kr.co.fixlog.databinding.ItemDocCardBinding
import kr.co.fixlog.helper.FolderNavigationHelper
import kr.co.fixlog.model.FileItem
import kr.co.fixlog.model.FileType
import kr.co.fixlog.util.AllDialog
import kr.co.fixlog.util.BottomNav
import kr.co.fixlog.util.DocumentActionsHelper
import kr.co.fixlog.util.LoadingIndicator
import kr.co.fixlog.util.launchWithLoading

/**
 * 문서(/documents) 화면.
 *
 * 구성(위 → 아래):
 *  1) 최근 문서: GET /api/documents(updateTime DESC) 상위 몇 건을 고정 카드(doc1~4)에 표시(문서만).
 *  2) 문서 리스트: 폴더 브라우저. 현재 경로(breadcrumb) + 폴더/문서 목록(RecyclerView).
 *     - 폴더 클릭 → 해당 폴더로 in-place 이동
 *     - 경로(breadcrumb) 세그먼트 클릭 → 그 위치로 이동(루트는 "루트(최상위)")
 *     - 문서 클릭 → 에디터, 문서 롱클릭 → CRUD 메뉴
 *
 * 다른 화면(Home)에서 특정 폴더로 진입시키려면 EXTRA_FOLDER_ID/NAME을 넘긴다.
 */
class DocumentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentsBinding
    private lateinit var recentCards: List<ItemDocCardBinding>
    private lateinit var browserAdapter: DocumentAdapter
    private lateinit var folderNav: FolderNavigationHelper
    private var browserInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDocumentsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        recentCards = listOf(binding.doc1, binding.doc2, binding.doc3, binding.doc4)

        binding.aiHint.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        setupBrowser()

        BottomNav.setup(this, binding.bottomNav, BottomNav.Tab.DOCS) { AllDialog.show(this) }
    }

    override fun onResume() {
        super.onResume()
        loadRecentDocuments()
        if (!browserInitialized) {
            browserInitialized = true
            val initialFolderId = intent.getStringExtra(EXTRA_FOLDER_ID)
            val initialFolderName = intent.getStringExtra(EXTRA_FOLDER_NAME)
            if (!initialFolderId.isNullOrBlank()) {
                folderNav.navigateInto(
                    FileItem(initialFolderId, initialFolderName.orEmpty(), FileType.FOLDER, "")
                )
            } else {
                folderNav.navigateToRoot()
            }
        } else {
            // 다른 화면에서 생성/수정/삭제 후 복귀 시 현재 위치를 다시 로드.
            folderNav.reload()
        }
    }

    // ---------------------------------------------------------------------
    // 최근 문서 (상단 고정 카드, 문서만)
    // ---------------------------------------------------------------------

    private fun loadRecentDocuments() {
        launchWithLoading {
            runCatching { DocumentApi.list(folderId = null, page = 0, size = recentCards.size) }
                .onSuccess { page -> bindRecent(page.items) }
                .onFailure { e ->
                    Log.w(TAG, "최근 문서 조회 실패", e)
                    bindRecent(emptyList())
                }
        }
    }

    private fun bindRecent(docs: List<DocumentDto>) {
        recentCards.forEachIndexed { index, card ->
            val doc = docs.getOrNull(index)
            if (doc == null) {
                card.root.visibility = View.GONE
            } else {
                card.root.visibility = View.VISIBLE
                bindDocCard(card, doc)
            }
        }
    }

    private fun bindDocCard(card: ItemDocCardBinding, doc: DocumentDto) {
        card.docIcon.setImageResource(R.drawable.ic_file)
        card.docTitle.text = doc.title.ifBlank { "Untitled" }
        card.docDesc.visibility = View.VISIBLE
        card.docDesc.text = doc.plainText?.trim().orEmpty()
        card.docMeta.visibility = View.VISIBLE
        card.docMeta.text = buildMeta(doc)
        card.docStatus.visibility = View.GONE
        card.root.setOnClickListener { openEditor(doc.documentId, doc.title) }
        card.root.setOnLongClickListener {
            DocumentActionsHelper.show(this, lifecycleScope, doc.documentId, doc.title) {
                loadRecentDocuments()
                folderNav.reload()
            }
            true
        }
    }

    // ---------------------------------------------------------------------
    // 문서 리스트 (폴더 브라우저)
    // ---------------------------------------------------------------------

    private fun setupBrowser() {
        browserAdapter = DocumentAdapter(
            onFolderClick = { folder -> folderNav.navigateInto(folder) },
            onFileClick = { file -> openEditor(file.id, file.name) },
            onItemLongClick = { item ->
                if (item.type == FileType.FILE) {
                    DocumentActionsHelper.show(this, lifecycleScope, item.id, item.name) {
                        loadRecentDocuments()
                        folderNav.reload()
                    }
                }
            }
        )
        binding.rvBrowser.apply {
            layoutManager = LinearLayoutManager(this@DocumentsActivity)
            adapter = browserAdapter
            isNestedScrollingEnabled = false
        }

        folderNav = FolderNavigationHelper(scope = lifecycleScope)
        folderNav.onLoadingChanged = { loading ->
            if (loading) LoadingIndicator.show(this) else LoadingIndicator.hide(this)
        }
        folderNav.onError = { e ->
            Log.w(TAG, "폴더 조회 실패", e)
            Toast.makeText(
                this,
                "폴더를 불러오지 못했습니다: ${e.message ?: "알 수 없는 오류"}",
                Toast.LENGTH_SHORT
            ).show()
        }
        folderNav.onNavigationChanged = { items, _, _ ->
            browserAdapter.updateItems(items)
            renderBreadcrumb()
        }
    }

    /** ["루트(최상위)"] + 현재 경로 폴더명으로 breadcrumb를 그린다. 마지막(현재 위치) 외에는 클릭 시 해당 위치로 이동. */
    private fun renderBreadcrumb() {
        val container = binding.breadcrumb
        container.removeAllViews()

        val names = listOf(ROOT_LABEL) + folderNav.pathNames()
        names.forEachIndexed { index, name ->
            if (index > 0) container.addView(makeSeparator())
            val isLast = index == names.lastIndex
            container.addView(makeCrumb(name, isLast, depth = index))
        }
        binding.breadcrumbScroll.post {
            binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT)
        }
    }

    private fun makeCrumb(text: String, isCurrent: Boolean, depth: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(dp(2), dp(2), dp(2), dp(2))
            if (isCurrent) {
                setTextColor(ContextCompat.getColor(this@DocumentsActivity, R.color.editor_title))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            } else {
                setTextColor(ContextCompat.getColor(this@DocumentsActivity, R.color.editor_blue))
                setOnClickListener { folderNav.navigateToDepth(depth) }
            }
        }
    }

    private fun makeSeparator(): TextView = TextView(this).apply {
        text = " / "
        textSize = 14f
        setTextColor(ContextCompat.getColor(this@DocumentsActivity, R.color.editor_meta))
    }

    // ---------------------------------------------------------------------
    // 공통
    // ---------------------------------------------------------------------

    /** 최근 문서 메타: 작성자 식별자 대신 수정 날짜(없으면 생성 날짜, YYYY-MM-DD)를 표시. */
    private fun buildMeta(doc: DocumentDto): String =
        (doc.updateTime ?: doc.createTime)?.take(10).orEmpty()

    private fun openEditor(documentId: String, title: String) {
        startActivity(Intent(this, EditorActivity::class.java).apply {
            putExtra(EditorActivity.EXTRA_FILE_ID, documentId)
            putExtra(EditorActivity.EXTRA_FILE_NAME, title)
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "DocumentsActivity"

        private const val ROOT_LABEL = "루트(최상위)"

        /** 특정 폴더로 브라우저를 열 때 넘기는 익스트라. */
        const val EXTRA_FOLDER_ID = "folder_id"
        const val EXTRA_FOLDER_NAME = "folder_name"
    }
}
