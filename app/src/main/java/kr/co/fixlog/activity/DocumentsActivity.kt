package kr.co.fixlog.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.adapter.DocumentAdapter
import kr.co.fixlog.data.remote.DocumentApi
import kr.co.fixlog.data.remote.FolderApi
import kr.co.fixlog.data.remote.dto.FolderRequest
import kr.co.fixlog.databinding.ActivityDocumentsBinding
import kr.co.fixlog.helper.FolderNavigationHelper
import kr.co.fixlog.model.FileItem
import kr.co.fixlog.model.FileType
import kr.co.fixlog.util.BottomNav
import kr.co.fixlog.util.OwnerNames
import kr.co.fixlog.util.TreeItemActionsHelper

/**
 * 문서(/documents) 화면. 폴더/문서를 트리 구조 리스트로만 표시한다.
 *
 * - 상단 제목("문서") + 현재 경로(breadcrumb).
 * - 리스트: 폴더/문서 아이콘 + 이름 + 라벨 + 소유자·수정일 + 더보기(⋮).
 *   - 폴더 클릭 → 하위로 이동, 문서 클릭 → 에디터.
 *   - 브레드크럼 세그먼트 클릭 → 해당 위치로 이동.
 *   - 더보기 → 이름 변경/이동/삭제 팝업([TreeItemActionsHelper]).
 *
 * 다른 화면(Home)에서 특정 폴더로 진입시키려면 EXTRA_FOLDER_ID/NAME을 넘긴다.
 */
class DocumentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentsBinding
    private lateinit var browserAdapter: DocumentAdapter
    private lateinit var folderNav: FolderNavigationHelper
    private var browserInitialized = false

    /** 현재 표시 중인 아이템(메타 보강 후 갱신용). */
    private var currentItems: List<FileItem> = emptyList()

    /** 폴더 이동 세대 카운터. 보강 결과가 이전 폴더 것이면 반영하지 않도록 사용. */
    private var loadGeneration = 0

    /** true면 즐겨찾기한 문서만(플랫) 표시, false면 기존 폴더 트리. */
    private var favoritesOnly = false

    /** 현재 폴더의 원본(미필터) 아이템. 접근 범위 로드 후 재필터에 사용. */
    private var rawItems: List<FileItem> = emptyList()

    /** 접근(공유) 범위: 내가 공유받은 폴더/문서 id 집합 + 내 userId. 협업 WS에서 노출 필터에 사용. */
    private var sharedIds: Set<String> = emptySet()
    private var myUserId: String? = null
    private var accessLoaded = false

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

        setupBrowser()
        binding.btnFilter.setOnClickListener { showFilterDialog() }
        binding.btnAdd.setOnClickListener { showCreateMenu() }
        BottomNav.setup(this, binding.bottomNav, BottomNav.Tab.DOCS)
    }

    override fun onResume() {
        super.onResume()
        loadAccessScope()
        if (favoritesOnly) {
            browserInitialized = true
            loadFavorites()
            return
        }
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

    /**
     * 우측 상단 필터 버튼 → 체크박스 팝업. 체크/해제 시 취소·적용 버튼 없이 바로 필터를 적용한다.
     */
    private fun showFilterDialog() {
        val popup = android.widget.PopupMenu(this, binding.btnFilter)
        val item = popup.menu.add(0, 1, 0, getString(R.string.docs_favorites_only))
        item.isCheckable = true
        item.isChecked = favoritesOnly
        popup.setOnMenuItemClickListener {
            applyFavoritesFilter(!favoritesOnly)
            true
        }
        popup.show()
    }

    /**
     * 접근(공유) 범위 로드. 협업 워크스페이스에서는 "공유받은 것 + 내가 만든 것"만 노출한다(§5).
     * 개인 워크스페이스/미선택이면 필터하지 않는다. 결과 로드 후 현재 목록을 다시 필터링한다.
     */
    private fun loadAccessScope() {
        if (accessLoaded) return
        if (kr.co.fixlog.util.WorkspaceManager.getSelectedId(this) == null) {
            accessLoaded = true // 개인 WS → 필터 없음
            return
        }
        lifecycleScope.launch {
            val shared = runCatching { kr.co.fixlog.data.remote.ShareApi.sharedWithMe() }.getOrNull()
            sharedIds = buildSet {
                shared?.folders?.forEach { add(it.folderId) }
                shared?.documents?.forEach { add(it.documentId) }
            }
            myUserId = runCatching { kr.co.fixlog.data.remote.AuthApi.session() }.getOrNull()
                ?.let { runCatching { org.json.JSONObject(it).optString("userId") }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
            accessLoaded = true
            if (!favoritesOnly) applyAccessAndRender(loadGeneration)
        }
    }

    /** 협업 WS에서만 노출 필터를 적용한다. */
    private fun shouldFilterAccess(): Boolean =
        kr.co.fixlog.util.WorkspaceManager.getSelectedId(this) != null

    private fun isVisible(item: FileItem): Boolean {
        if (item.createUser != null && item.createUser == myUserId) return true
        if (sharedIds.contains(item.id)) return true
        // 공유받은 폴더 하위(상속): 현재 경로의 상위 폴더가 공유 대상이면 모두 노출.
        return folderNav.pathIds().any { sharedIds.contains(it) }
    }

    /**
     * rawItems에 접근 필터를 적용 → 소유자 해석(enrich)까지 끝낸 뒤 실제 리스트를 표시한다.
     * 그 전까지는 skeleton을 유지한다(빈 목록이면 즉시 표시).
     */
    private fun applyAccessAndRender(generation: Int) {
        val visible = if (!shouldFilterAccess() || !accessLoaded) rawItems
        else rawItems.filter { isVisible(it) }
        currentItems = visible
        if (visible.isEmpty()) {
            browserAdapter.updateItems(emptyList())
            hideSkeleton()
        } else {
            showSkeleton()
            enrichDocuments(generation, visible)
        }
    }

    /** 필터 적용: 즐겨찾기 전용 ↔ 기존 트리 전환. */
    private fun applyFavoritesFilter(on: Boolean) {
        favoritesOnly = on
        // 필터 활성 시 아이콘 강조.
        binding.btnFilter.setColorFilter(
            ContextCompat.getColor(this, if (on) R.color.editor_blue else R.color.editor_meta)
        )
        if (on) {
            binding.breadcrumbScroll.visibility = View.GONE
            loadFavorites()
        } else {
            binding.breadcrumbScroll.visibility = View.VISIBLE
            folderNav.reload()
        }
    }

    /** 우측 상단 + 버튼 → 새 폴더 / 새 문서 선택 팝업. */
    private fun showCreateMenu() {
        val popup = android.widget.PopupMenu(this, binding.btnAdd)
        popup.menu.add(0, 1, 0, getString(R.string.docs_new_document))
        popup.menu.add(0, 2, 1, getString(R.string.docs_new_folder))
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> createDocument()
                2 -> showCreateFolderDialog()
            }
            true
        }
        popup.show()
    }

    /** 현재 폴더(즐겨찾기 모드면 루트)에 새 문서를 만들고 에디터로 이동. */
    private fun createDocument() {
        val parent = if (favoritesOnly) null else folderNav.currentFolderId()
        lifecycleScope.launch {
            runCatching { DocumentApi.create(parent, getString(R.string.common_untitled)) }
                .onSuccess { doc -> openEditor(doc.documentId, doc.title) }
                .onFailure { e ->
                    Log.w(TAG, "문서 생성 실패", e)
                    Toast.makeText(this@DocumentsActivity, getString(R.string.docs_create_doc_failed, e.message ?: getString(R.string.common_unknown_error)), Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 새 폴더 이름 입력 후 현재 위치에 생성. */
    private fun showCreateFolderDialog() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.docs_folder_name_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.docs_new_folder))
            .setView(input)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_create)) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(this, getString(R.string.docs_enter_folder_name), Toast.LENGTH_SHORT).show()
                } else {
                    createFolder(name)
                }
            }
            .show()
    }

    private fun createFolder(name: String) {
        val parent = if (favoritesOnly) null else folderNav.currentFolderId()
        lifecycleScope.launch {
            runCatching { FolderApi.createFolder(FolderRequest(parentId = parent, folderName = name)) }
                .onSuccess {
                    Toast.makeText(this@DocumentsActivity, getString(R.string.docs_folder_created), Toast.LENGTH_SHORT).show()
                    if (favoritesOnly) applyFavoritesFilter(false) else refresh()
                }
                .onFailure { e ->
                    Log.w(TAG, "폴더 생성 실패", e)
                    Toast.makeText(this@DocumentsActivity, getString(R.string.docs_create_folder_failed, e.message ?: getString(R.string.common_unknown_error)), Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 필터/기타 화면 상태에 맞춘 새로고침. */
    private fun refresh() {
        if (favoritesOnly) loadFavorites() else folderNav.reload()
    }

    /** 즐겨찾기한 문서만 플랫 리스트로 로드(GET /api/documents/favorites) 후 메타 보강. */
    private fun loadFavorites() {
        showSkeleton()
        lifecycleScope.launch {
            val favs = runCatching { DocumentApi.getFavorites() }.getOrElse {
                Log.w(TAG, "즐겨찾기 목록 조회 실패", it)
                emptyList()
            }
            val items = favs.map { doc ->
                FileItem(
                    id = doc.documentId,
                    name = doc.title,
                    type = FileType.FILE,
                    date = (doc.updateTime ?: doc.createTime)?.take(10).orEmpty(),
                    parentId = doc.folderId,
                    owner = doc.createUserName ?: doc.createUser,
                    labels = doc.labels?.mapNotNull { l -> l.labelName?.takeIf(String::isNotBlank) }.orEmpty()
                )
            }
            loadGeneration++
            currentItems = items
            if (items.isEmpty()) {
                browserAdapter.updateItems(emptyList())
                hideSkeleton()
                Toast.makeText(this@DocumentsActivity, getString(R.string.docs_no_favorites), Toast.LENGTH_SHORT).show()
            } else {
                // 소유자 해석(enrich)까지 끝난 뒤 리스트 표시 + skeleton 해제.
                enrichDocuments(loadGeneration, items)
            }
        }
    }

    private fun setupBrowser() {
        browserAdapter = DocumentAdapter(
            onFolderClick = { folder -> folderNav.navigateInto(folder) },
            onFileClick = { file -> openEditor(file.id, file.name) },
            onMoreClick = { item -> showItemActions(item) },
            onItemLongClick = { item -> showItemActions(item) }
        )
        binding.rvBrowser.apply {
            layoutManager = LinearLayoutManager(this@DocumentsActivity)
            adapter = browserAdapter
        }

        folderNav = FolderNavigationHelper(scope = lifecycleScope)
        folderNav.onLoadingChanged = { loading ->
            // 폴더 콘텐츠 조회 시작 시 skeleton 표시(실제 리스트는 enrich 완료 후 노출).
            if (loading) showSkeleton()
        }
        folderNav.onError = { e ->
            Log.w(TAG, "폴더 조회 실패", e)
            Toast.makeText(
                this,
                getString(R.string.docs_load_folder_failed, e.message ?: getString(R.string.common_unknown_error)),
                Toast.LENGTH_SHORT
            ).show()
        }
        folderNav.onNavigationChanged = { items, _, _ ->
            loadGeneration++
            rawItems = items
            renderBreadcrumb()
            applyAccessAndRender(loadGeneration)
        }
    }

    /**
     * 폴더 콘텐츠(목록) 응답에는 소유자/수정일(폴더·문서), 문서 제목/라벨이 빠져 있을 수 있어,
     * 각 항목의 상세를 조회해 리스트를 보강한다.
     *  - 문서: GET /api/documents/{id} (제목·작성자명·수정일) + GET .../labels (라벨)
     *  - 폴더: GET /api/folders/{id} (소유자·수정일). 폴더는 라벨이 없다.
     *  - 폴더가 다시 로드되면 [loadGeneration]이 바뀌어, 이전 폴더의 지연 응답은 반영하지 않는다.
     */
    private fun enrichDocuments(generation: Int, items: List<FileItem>) {
        if (items.isEmpty()) { hideSkeleton(); return }
        lifecycleScope.launch {
            // 소유자(userId) → 이름 매핑(구성원 목록 + 세션). 웹의 useOwnerName 과 동일.
            val ownerNames = OwnerNames.load(this@DocumentsActivity)
            val enrichedById: Map<String, FileItem> = coroutineScope {
                items.map { item ->
                    async {
                        item.id to if (item.type == FileType.FILE) enrichFile(item, ownerNames)
                        else enrichFolder(item, ownerNames)
                    }
                }.awaitAll().toMap()
            }
            // 그 사이 폴더가 바뀌었으면 무시(현재 세대의 skeleton은 그 세대가 끝낼 때 해제).
            if (generation != loadGeneration) return@launch
            val merged = currentItems.map { enrichedById[it.id] ?: it }
            currentItems = merged
            // 소유자 해석까지 끝난 최종본을 한 번에 표시하고 skeleton 해제.
            browserAdapter.updateItems(merged)
            hideSkeleton()
        }
    }

    /** 리스트 로딩 중 skeleton 표시(실제 RecyclerView는 숨김) + 반짝임 시작. */
    private fun showSkeleton() {
        binding.llSkeleton.visibility = View.VISIBLE
        binding.rvBrowser.visibility = View.INVISIBLE
        kr.co.fixlog.util.Shimmer.start(binding.llSkeleton)
    }

    /** 실제 리스트 표시(skeleton 숨김) + 반짝임 정지. */
    private fun hideSkeleton() {
        kr.co.fixlog.util.Shimmer.stop(binding.llSkeleton)
        binding.llSkeleton.visibility = View.GONE
        binding.rvBrowser.visibility = View.VISIBLE
    }

    private suspend fun enrichFile(item: FileItem, ownerNames: Map<String, String>): FileItem {
        val meta = runCatching { DocumentApi.getMeta(item.id) }.getOrNull()
        val labels = runCatching { DocumentApi.getLabels(item.id) }.getOrDefault(emptyList())
        val owner = meta?.createUserName?.takeIf { it.isNotBlank() }
            ?: OwnerNames.resolve(ownerNames, meta?.createUser)
            ?: item.owner
        return item.copy(
            name = meta?.title?.takeIf { it.isNotBlank() } ?: item.name,
            owner = owner,
            date = (meta?.updateTime ?: meta?.createTime)?.take(10)?.takeIf { it.isNotBlank() } ?: item.date,
            labels = labels
        )
    }

    private suspend fun enrichFolder(item: FileItem, ownerNames: Map<String, String>): FileItem {
        val folder = runCatching { FolderApi.getFolder(item.id) }.getOrNull() ?: return item
        // 폴더 소유자 = 생성자(createUser)를 이름으로 해석. 알 수 없으면 생략(날짜만 표시).
        val owner = OwnerNames.resolve(ownerNames, folder.createUser)
        return item.copy(
            name = folder.folderName.takeIf { it.isNotBlank() } ?: item.name,
            owner = owner,
            date = (folder.updateTime ?: folder.createTime)?.take(10)?.takeIf { it.isNotBlank() } ?: item.date
        )
    }

    /** 더보기(⋮)/롱클릭 → 이름 변경/이동/삭제 팝업. 성공 시 현재 화면 상태에 맞춰 새로고침. */
    private fun showItemActions(item: FileItem) {
        TreeItemActionsHelper.show(this, lifecycleScope, item) { refresh() }
    }

    /** ["루트(최상위)"] + 현재 경로 폴더명으로 breadcrumb를 그린다. 마지막(현재 위치) 외에는 클릭 시 해당 위치로 이동. */
    private fun renderBreadcrumb() {
        val container = binding.breadcrumb
        container.removeAllViews()

        val names = listOf(getString(R.string.docs_root_label)) + folderNav.pathNames()
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

    private fun openEditor(documentId: String, title: String) {
        startActivity(Intent(this, EditorActivity::class.java).apply {
            putExtra(EditorActivity.EXTRA_FILE_ID, documentId)
            putExtra(EditorActivity.EXTRA_FILE_NAME, title)
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "DocumentsActivity"

        /** 특정 폴더로 브라우저를 열 때 넘기는 익스트라. */
        const val EXTRA_FOLDER_ID = "folder_id"
        const val EXTRA_FOLDER_NAME = "folder_name"
    }
}
