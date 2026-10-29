package kr.co.fixlog.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.adapter.DocumentAdapter
import kr.co.fixlog.data.remote.BulkApi
import kr.co.fixlog.data.remote.DocumentApi
import kr.co.fixlog.data.remote.FolderApi
import kr.co.fixlog.data.remote.SearchApi
import kr.co.fixlog.data.remote.dto.BulkResultDto
import kr.co.fixlog.data.remote.dto.FolderRequest
import kr.co.fixlog.data.remote.dto.FolderTreeDto
import kr.co.fixlog.databinding.ActivityDocumentsBinding
import kr.co.fixlog.helper.FolderNavigationHelper
import kr.co.fixlog.model.FileItem
import kr.co.fixlog.model.FileType
import kr.co.fixlog.util.BottomNav
import kr.co.fixlog.util.DocumentDownloader
import kr.co.fixlog.util.FolderPickerDialog
import kr.co.fixlog.util.LoadingIndicator
import kr.co.fixlog.util.OwnerNames
import kr.co.fixlog.util.TreeItemActionsHelper
import kr.co.fixlog.util.CoachMark

/**
 * 문서(/documents) 화면. 폴더/문서를 트리 구조 리스트로만 표시한다.
 *
 * - 상단 제목("문서") + 현재 경로(breadcrumb).
 * - 리스트: 폴더/문서 아이콘 + 이름 + 라벨 + 소유자·수정일 + 더보기(⋮).
 *   - 폴더 클릭 → 하위로 이동, 문서 클릭 → 에디터.
 *   - 브레드크럼 세그먼트 클릭 → 해당 위치로 이동.
 *   - 더보기 → 이름 변경/이동/삭제 팝업([TreeItemActionsHelper]).
 *   - 길게 누르기 → 다중 선택 모드(왼쪽 체크박스 + 하단 기능바: 전체 선택/이동/다운로드/삭제).
 * - 상단 검색창: 폴더명 + 문서 제목 검색(GET /search/title, 워크스페이스 전체). 입력 중에는 검색 결과만 보인다.
 *
 * 다른 화면(Home)에서 특정 폴더로 진입시키려면 EXTRA_FOLDER_ID/NAME을 넘긴다.
 */
class DocumentsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentsBinding
    private lateinit var browserAdapter: DocumentAdapter
    private lateinit var folderNav: FolderNavigationHelper
    /** 아래로 당겨 새로고침 진행 중 여부(이 동안은 skeleton을 띄우지 않는다). */
    private var pullRefreshing = false
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

    /**
     * 이 세션에서 내가 방금 생성한 폴더/문서 id. 협업 WS 접근 필터에서 목록 응답의 createUser가
     * 비어 있어도(새로 만든 직후) 숨겨지지 않도록 항상 노출시킨다.
     */
    private val myCreatedIds = mutableSetOf<String>()

    /** 코치마크 튜토리얼 중이면 서버 대신 샘플 목록을 보여준다. 끝나면 실제 목록을 로드한다. */
    private var tutorial = false

    /** 현재 검색어(trim). 비어 있지 않으면 목록은 검색 결과다. */
    private var searchQuery = ""
    private var searchJob: Job? = null

    /** 다중 선택된 id. null 이면 선택 모드가 아니다. */
    private var selectedIds: MutableSet<String>? = null

    /** 선택 모드에서 뒤로가기 → 선택 모드 종료. */
    private val selectionBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = exitSelection()
    }

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
        setupSearch()
        setupBulkBar()
        onBackPressedDispatcher.addCallback(this, selectionBack)
        binding.btnFilter.setOnClickListener { showFilterDialog() }
        binding.btnAdd.setOnClickListener { showCreateMenu() }
        BottomNav.setup(this, binding.bottomNav, BottomNav.Tab.DOCS)
        tutorial = CoachMark.show(this, "documents",
            CoachMark.Step(binding.btnAdd, R.string.coach_docs_add),
            CoachMark.Step(binding.btnFilter, R.string.coach_docs_filter),
            CoachMark.Step(binding.breadcrumbScroll, R.string.coach_docs_breadcrumb),
            CoachMark.Step(R.string.coach_docs_sample_items) {
                listOf(binding.rvBrowser.getChildAt(0), binding.rvBrowser.getChildAt(1))
            },
            CoachMark.Step(R.string.coach_docs_sample_more) { listOf(binding.rvBrowser.getChildAt(1)?.findViewById(R.id.btn_more)) },
            CoachMark.Step(binding.bottomNav.root, R.string.coach_docs_nav)
        ) {
            tutorial = false
            loadBrowser()
        }
    }

    override fun onResume() {
        super.onResume()
        if (tutorial) showSamples() else loadBrowser()
    }

    /** 튜토리얼용 샘플 목록. 서버에 저장되지 않으며 화면에만 표시한다. */
    private fun showSamples() {
        val today = java.time.LocalDate.now().toString()
        renderBreadcrumb()
        browserAdapter.updateItems(listOf(
            FileItem("sample-folder", getString(R.string.sample_folder), FileType.FOLDER, today, owner = "FixLog"),
            FileItem("sample-doc-1", getString(R.string.sample_doc_deploy), FileType.FILE, today, owner = "FixLog",
                labels = listOf(getString(R.string.sample_label))),
            FileItem("sample-doc-2", getString(R.string.sample_doc_incident), FileType.FILE, today, owner = "FixLog")
        ))
        hideSkeleton()
    }

    private fun loadBrowser() {
        loadAccessScope()
        if (searchQuery.isNotEmpty()) {
            refresh()
            return
        }
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
     * 우측 상단 필터 버튼 → 선택형 드롭다운. 탭하면 취소·적용 버튼 없이 바로 필터를 켜고 끈다.
     */
    private fun showFilterDialog() {
        kr.co.fixlog.util.DropdownMenu.show(binding.btnFilter, listOf(
            kr.co.fixlog.util.DropdownMenu.Item(
                getString(R.string.docs_favorites_only),
                if (favoritesOnly) R.drawable.ic_star_filled else R.drawable.ic_star_outline,
                checked = favoritesOnly
            ) { applyFavoritesFilter(!favoritesOnly) }
        ))
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
        if (myCreatedIds.contains(item.id)) return true
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
        exitSelection()
        if (searchQuery.isNotEmpty()) return // 검색 중엔 검색 결과 우선(웹과 동일). 검색 종료 시 반영된다.
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
        kr.co.fixlog.util.DropdownMenu.show(binding.btnAdd, listOf(
            kr.co.fixlog.util.DropdownMenu.Item(getString(R.string.docs_new_document), R.drawable.ic_doc_add) { createDocument() },
            kr.co.fixlog.util.DropdownMenu.Item(getString(R.string.docs_new_folder), R.drawable.ic_folder_plus) { showCreateFolderDialog() }
        ))
    }

    /** 현재 폴더(즐겨찾기 모드면 루트)에 새 문서를 만들고 에디터로 이동. */
    private fun createDocument() {
        val parent = if (favoritesOnly) null else folderNav.currentFolderId()
        lifecycleScope.launch {
            runCatching { DocumentApi.create(parent, getString(R.string.common_untitled)) }
                .onSuccess { doc ->
                    myCreatedIds.add(doc.documentId) // 협업 WS 접근 필터에서 숨겨지지 않도록 기록
                    openEditor(doc.documentId, doc.title)
                }
                .onFailure { e ->
                    Log.w(TAG, "문서 생성 실패", e)
                    Toast.makeText(this@DocumentsActivity, getString(R.string.docs_create_doc_failed, e.message ?: getString(R.string.common_unknown_error)), Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 새 폴더 이름 입력 후 현재 위치에 생성. */
    private fun showCreateFolderDialog() {
        val input = kr.co.fixlog.util.DialogInput(this, hint = getString(R.string.docs_folder_name_hint))
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.docs_new_folder))
            .setView(input.view)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_create)) { _, _ ->
                val name = input.value
                if (name.isBlank()) {
                    Toast.makeText(this, getString(R.string.docs_enter_folder_name), Toast.LENGTH_SHORT).show()
                } else {
                    createFolder(name)
                }
            }
            .create()
        input.showWithKeyboard(dialog)
    }

    private fun createFolder(name: String) {
        val parent = if (favoritesOnly) null else folderNav.currentFolderId()
        lifecycleScope.launch {
            runCatching { FolderApi.createFolder(FolderRequest(parentId = parent, folderName = name)) }
                .onSuccess { created ->
                    myCreatedIds.add(created.folderId) // 협업 WS 접근 필터에서 숨겨지지 않도록 기록
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
        when {
            searchQuery.isNotEmpty() -> lifecycleScope.launch { runSearch() }
            favoritesOnly -> loadFavorites()
            else -> folderNav.reload()
        }
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
            if (searchQuery.isNotEmpty()) return@launch
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
            onFolderClick = { folder -> openFolder(folder) },
            onFileClick = { file -> openEditor(file.id, file.name) },
            onMoreClick = { item -> showItemActions(item) },
            onItemLongClick = { item -> onItemLongClick(item) },
            onToggleSelect = { item -> toggleSelect(item) }
        )
        binding.rvBrowser.apply {
            layoutManager = LinearLayoutManager(this@DocumentsActivity)
            adapter = browserAdapter
        }

        setupPullToRefresh()

        folderNav = FolderNavigationHelper(scope = lifecycleScope)
        folderNav.onLoadingChanged = { loading ->
            // 폴더 콘텐츠 조회 시작 시 skeleton 표시(실제 리스트는 enrich 완료 후 노출).
            if (loading) showSkeleton()
        }
        folderNav.onError = { e ->
            Log.w(TAG, "폴더 조회 실패", e)
            hideSkeleton() // 실패 시에도 skeleton/새로고침 표시를 걷어낸다.
            Toast.makeText(
                this,
                getString(R.string.docs_load_folder_failed, e.message ?: getString(R.string.common_unknown_error)),
                Toast.LENGTH_SHORT
            ).show()
        }
        folderNav.onNavigationChanged = { items, _, _ ->
            rawItems = items
            renderBreadcrumb()
            exitSelection()
            // 검색 중에 늦게 도착한 폴더 응답은 검색 결과를 덮지 않는다(검색 종료 시 다시 로드).
            if (searchQuery.isEmpty()) {
                loadGeneration++
                applyAccessAndRender(loadGeneration)
            }
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

    /**
     * 아래로 당겨 새로고침: 현재 위치(또는 즐겨찾기 필터)를 다시 로드한다.
     * 당겨서 새로고침 중에는 skeleton 대신 기존 리스트 + 상단 스피너로 로딩을 표시한다.
     * 리스트가 FrameLayout 안에 있어, 맨 위인지 여부는 RecyclerView 기준으로 판단한다.
     */
    private fun setupPullToRefresh() {
        binding.swipeRefresh.apply {
            setColorSchemeResources(R.color.editor_blue)
            setProgressBackgroundColorSchemeResource(R.color.surface)
            setOnChildScrollUpCallback { _, _ -> binding.rvBrowser.canScrollVertically(-1) }
            setOnRefreshListener {
                pullRefreshing = true
                refresh()
            }
        }
    }

    /** 리스트 로딩 중 skeleton 표시(실제 RecyclerView는 숨김) + 반짝임 시작. */
    private fun showSkeleton() {
        if (pullRefreshing) return // 당겨서 새로고침 중엔 기존 리스트를 유지(스피너로 표시)
        binding.llSkeleton.visibility = View.VISIBLE
        binding.rvBrowser.visibility = View.INVISIBLE
        kr.co.fixlog.util.Shimmer.start(binding.llSkeleton)
    }

    /** 실제 리스트 표시(skeleton 숨김) + 반짝임 정지. */
    private fun hideSkeleton() {
        pullRefreshing = false
        binding.swipeRefresh.isRefreshing = false
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

    // ---------------------------------------------------------------------
    // 검색
    // ---------------------------------------------------------------------

    /** 입력을 300ms 디바운스해 제목 검색. 지우면 원래 목록(폴더/즐겨찾기)으로 돌아간다. */
    private fun setupSearch() {
        binding.etSearch.doAfterTextChanged { text ->
            binding.btnClearSearch.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            val q = text?.toString()?.trim().orEmpty()
            if (q == searchQuery) return@doAfterTextChanged
            searchQuery = q
            searchJob?.cancel()
            exitSelection()
            if (q.isEmpty()) {
                binding.tvEmpty.visibility = View.GONE
                binding.breadcrumbScroll.visibility = if (favoritesOnly) View.GONE else View.VISIBLE
                refresh()
            } else {
                binding.breadcrumbScroll.visibility = View.GONE
                searchJob = lifecycleScope.launch {
                    delay(300)
                    runSearch()
                }
            }
        }
        binding.etSearch.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            true
        }
        binding.btnClearSearch.setOnClickListener { binding.etSearch.text = null }
    }

    /** GET /search/title. 권한 필터는 서버가 적용하므로 접근 필터 없이, 소유자 이름만 해석해 표시한다. */
    private suspend fun runSearch() {
        val q = searchQuery
        if (q.isEmpty()) return
        showSkeleton()
        val generation = ++loadGeneration
        val result = runCatching {
            val found = SearchApi.searchTitle(q)
            val ownerNames = OwnerNames.load(this)
            found.folders.map {
                FileItem(
                    id = it.folderId,
                    name = it.folderName,
                    type = FileType.FOLDER,
                    date = (it.updateTime ?: it.createTime)?.take(10).orEmpty(),
                    parentId = it.parentId,
                    owner = OwnerNames.resolve(ownerNames, it.createUser),
                    createUser = it.createUser
                )
            } + found.documents.map {
                FileItem(
                    id = it.documentId,
                    name = it.title,
                    type = FileType.FILE,
                    date = (it.updateTime ?: it.createTime)?.take(10).orEmpty(),
                    parentId = it.folderId,
                    owner = it.createUserName ?: OwnerNames.resolve(ownerNames, it.createUser),
                    createUser = it.createUser,
                    labels = it.labels?.mapNotNull { l -> l.labelName?.takeIf(String::isNotBlank) }.orEmpty()
                )
            }
        }
        // 그 사이 검색어가 바뀌었거나 다른 목록이 로드됐으면 버린다.
        if (generation != loadGeneration || q != searchQuery) return
        result
            .onSuccess { items ->
                currentItems = items
                browserAdapter.updateItems(items)
                binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
            .onFailure { e ->
                Log.w(TAG, "제목 검색 실패", e)
                toast(getString(R.string.docs_search_failed, e.message ?: getString(R.string.common_unknown_error)))
            }
        hideSkeleton()
    }

    /**
     * 폴더 열기. 검색 결과의 폴더면 트리에서 전체 경로를 찾아 그 위치로 이동하고 검색을 끝낸다
     * (검색어 지우기 → refresh 가 새 경로를 로드). 트리에서 못 찾으면 그 폴더만 경로로 쓴다.
     */
    private fun openFolder(folder: FileItem) {
        if (searchQuery.isEmpty()) {
            folderNav.navigateInto(folder)
            return
        }
        lifecycleScope.launch {
            val tree = runCatching { FolderApi.getFolderTree() }.getOrNull().orEmpty()
            folderNav.setPath(findFolderPath(tree, folder.id) ?: listOf(folder.id to folder.name))
            if (favoritesOnly) applyFavoritesFilter(false)
            hideKeyboard()
            binding.etSearch.text = null
        }
    }

    /** 폴더 트리에서 targetId 까지의 경로(루트→대상, (id, 이름)). 없으면 null. */
    private fun findFolderPath(nodes: List<FolderTreeDto>, targetId: String): List<Pair<String, String>>? {
        for (node in nodes) {
            val self = node.folderId to node.folderName
            if (node.folderId == targetId) return listOf(self)
            findFolderPath(node.children, targetId)?.let { return listOf(self) + it }
        }
        return null
    }

    private fun hideKeyboard() {
        binding.etSearch.clearFocus()
        WindowCompat.getInsetsController(window, binding.etSearch).hide(WindowInsetsCompat.Type.ime())
    }

    // ---------------------------------------------------------------------
    // 다중 선택 + 일괄 작업(/api/bulk)
    // ---------------------------------------------------------------------

    /**
     * 길게 누르기: 그 항목을 선택하며 선택 모드로 들어간다(이미 선택 모드면 토글). 검색 결과에서도 가능.
     * 즐겨찾기 필터 중에는 다중 선택을 막고 기존처럼 항목 메뉴를 띄운다.
     */
    private fun onItemLongClick(item: FileItem) {
        when {
            selectedIds != null -> toggleSelect(item)
            favoritesOnly -> showItemActions(item)
            else -> {
                selectedIds = mutableSetOf(item.id)
                renderSelection()
            }
        }
    }

    private fun toggleSelect(item: FileItem) {
        val selected = selectedIds ?: return
        if (!selected.remove(item.id)) selected.add(item.id)
        renderSelection()
    }

    private fun exitSelection() {
        if (selectedIds == null) return
        selectedIds = null
        renderSelection()
    }

    /** 체크박스/제목("N개 선택됨")/하단 기능바 ↔ 하단 네비 전환. */
    private fun renderSelection() {
        val selected = selectedIds
        browserAdapter.setSelection(selected?.toSet())
        binding.tvTitle.text = if (selected == null) getString(R.string.nav_docs)
        else getString(R.string.docs_selected_count, selected.size)
        binding.bulkBar.visibility = if (selected != null) View.VISIBLE else View.GONE
        binding.bottomNav.root.visibility = if (selected != null) View.GONE else View.VISIBLE
        selectionBack.isEnabled = selected != null
    }

    private fun setupBulkBar() {
        binding.btnBulkAll.setOnClickListener {
            val selected = selectedIds ?: return@setOnClickListener
            val all = currentItems.map { it.id }
            if (selected.containsAll(all)) selected.clear() else selected.addAll(all)
            renderSelection()
        }
        binding.btnBulkMove.setOnClickListener { bulkMove() }
        binding.btnBulkDownload.setOnClickListener { bulkDownload() }
        binding.btnBulkDelete.setOnClickListener { bulkDelete() }
        binding.btnBulkClose.setOnClickListener { exitSelection() }
    }

    /** 선택 항목을 (문서 id, 폴더 id)로 나눈다. 없으면 안내 후 null. */
    private fun selectedTargets(): Pair<List<String>, List<String>>? {
        val selected = selectedIds ?: return null
        val items = currentItems.filter { it.id in selected }
        if (items.isEmpty()) {
            toast(getString(R.string.docs_select_none))
            return null
        }
        val (docs, folders) = items.partition { it.type == FileType.FILE }
        return docs.map { it.id } to folders.map { it.id }
    }

    /** 이동 위치 선택(선택한 폴더와 그 하위는 후보에서 제외) 후 일괄 이동. */
    private fun bulkMove() {
        val (docIds, folderIds) = selectedTargets() ?: return
        lifecycleScope.launch {
            val tree = runCatching { FolderApi.getFolderTree() }.getOrElse { e ->
                Log.w(TAG, "폴더 트리 조회 실패", e)
                toast(getString(R.string.actions_load_folders_failed))
                return@launch
            }
            FolderPickerDialog.show(this@DocumentsActivity, getString(R.string.actions_select_move_destination), tree, folderIds.toSet()) { destId ->
                runBulk(R.string.docs_bulk_moved) { BulkApi.move(docIds, folderIds, destId) }
            }
        }
    }

    private fun bulkDelete() {
        val (docIds, folderIds) = selectedTargets() ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.docs_bulk_delete_title))
            .setMessage(getString(R.string.docs_bulk_delete_message, docIds.size + folderIds.size))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_delete)) { _, _ ->
                runBulk(R.string.docs_bulk_deleted) { BulkApi.remove(docIds, folderIds) }
            }
            .show()
    }

    /** ZIP 일괄 다운로드. 서버 한도를 넘으면 요청하지 않고 안내한다. */
    private fun bulkDownload() {
        val (docIds, folderIds) = selectedTargets() ?: return
        if (docIds.size > BulkApi.DOWNLOAD_MAX_DOCUMENTS || folderIds.size > BulkApi.DOWNLOAD_MAX_FOLDERS) {
            toast(getString(R.string.docs_bulk_download_limit, BulkApi.DOWNLOAD_MAX_DOCUMENTS, BulkApi.DOWNLOAD_MAX_FOLDERS))
            return
        }
        DocumentDownloader.downloadBulk(this, lifecycleScope, docIds, folderIds)
    }

    /** 일괄 작업 실행 → 결과 요약(부분 성공 포함) 안내 → 선택 해제 + 목록 새로고침. */
    private fun runBulk(doneRes: Int, block: suspend () -> BulkResultDto) {
        LoadingIndicator.show(this)
        lifecycleScope.launch {
            runCatching { block() }
                .onSuccess { r ->
                    toast(
                        if (r.failedCount == 0) getString(doneRes, r.succeededCount)
                        else getString(R.string.docs_bulk_partial, r.totalRequested, r.succeededCount, r.failedCount)
                    )
                    exitSelection()
                    refresh()
                }
                .onFailure { e ->
                    Log.w(TAG, "일괄 작업 실패", e)
                    toast(getString(R.string.docs_bulk_failed, e.message ?: getString(R.string.common_unknown_error)))
                }
            LoadingIndicator.hide(this@DocumentsActivity)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** 더보기(⋮)(즐겨찾기 중엔 롱클릭도) → 이름 변경/이동/삭제 팝업. 성공 시 현재 화면 상태에 맞춰 새로고침. */
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
