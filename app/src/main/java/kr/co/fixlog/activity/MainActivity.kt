package kr.co.fixlog.activity

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.adapter.DocumentAdapter
import kr.co.fixlog.adapter.DrawerAdapter
import kr.co.fixlog.data.SampleDataRepository
import kr.co.fixlog.data.remote.DocumentApi
import kr.co.fixlog.data.remote.FolderApi
import kr.co.fixlog.data.remote.dto.FolderRequest
import kr.co.fixlog.data.remote.dto.FolderTreeDto
import kr.co.fixlog.model.FileType
import kr.co.fixlog.databinding.ActivityMainBinding
import kr.co.fixlog.helper.FolderNavigationHelper
import kr.co.fixlog.util.LoadingIndicator
import kr.co.fixlog.util.TokenManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var documentAdapter: DocumentAdapter
    private lateinit var drawerAdapter: DrawerAdapter
    private lateinit var folderNavHelper: FolderNavigationHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply system bar insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupToolbar()
        setupDocumentList()
        setupDrawer()
        setupDrawerHeader()
        setupFolderNavigation()
        setupBottomNav()

        binding.fabNewFolder.setOnClickListener { showCreateFolderDialog() }
    }

    /**
     * 하단 네비게이션(공용 include) 클릭 연결(1차).
     * 홈/문서는 문서 목록 루트로 이동, 검색은 검색창 포커스, 챗은 안내 토스트.
     */
    private fun setupBottomNav() {
        val nav = binding.bottomNav
        val goRoot = {
            folderNavHelper.navigateToRoot()
            binding.drawerLayout.closeDrawers()
        }
        nav.navHome.setOnClickListener { goRoot() }
        nav.navDocs.setOnClickListener { goRoot() }
        nav.navSearch.setOnClickListener { binding.etSearch.requestFocus() }
        nav.navWrite.setOnClickListener {
            Toast.makeText(this, "새 문서 작성은 준비 중입니다", Toast.LENGTH_SHORT).show()
        }
        nav.navAll.setOnClickListener {
            Toast.makeText(this, "전체 보기는 준비 중입니다", Toast.LENGTH_SHORT).show()
        }
        // 목록 화면은 "문서"(/documents) 탭이 현재 위치 → 활성색 강조
        val blue = androidx.core.content.ContextCompat.getColor(this, R.color.editor_blue)
        (nav.navDocs.getChildAt(0) as? android.widget.ImageView)?.setColorFilter(blue)
        (nav.navDocs.getChildAt(1) as? android.widget.TextView)?.setTextColor(blue)
    }

    /**
     * drawer_nav_header의 tv_name / tv_address에 현재 로그인한 계정 정보를 바인딩한다.
     * - 데이터 출처: SharedPreferences("fixlog_user")의 user_name / user_email
     * - 둘 다 존재하면 표시, 하나라도 없거나 비어있으면 Log로 남기고 placeholder 유지
     * - 사용자 명세: "drawer를 열었을 때" 동작 → onCreate 첫 바인딩 + DrawerListener로 매번 새로고침
     */
    private fun setupDrawerHeader() {
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                applyDrawerHeader()
            }
        })
        applyDrawerHeader()
    }

    private fun applyDrawerHeader() {
        val tvName = findViewById<TextView?>(R.id.tv_name)
        val tvAddress = findViewById<TextView?>(R.id.tv_address)

        // TODO: 하드코딩 — drawer 사용자 정보 저장소. 추후 TokenManager 또는 별도 UserInfoStore로
        //  통합하고 /auth/session 응답 등으로 채우는 게 적절.
        val prefs = getSharedPreferences("fixlog_user", MODE_PRIVATE)
        val name = prefs.getString("user_name", null)
        val email = prefs.getString("user_email", null)

        if (!name.isNullOrBlank() && !email.isNullOrBlank()) {
            tvName?.text = name
            tvAddress?.text = email
        } else {
            Log.d(TAG, "drawer header: 사용자 정보 없음 (name=$name, email=$email)")
        }
    }

    /** Configure the hidden toolbar and menu button for drawer toggle */
    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // Menu button opens the drawer
        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    /** Initialize the main document RecyclerView */
    private fun setupDocumentList() {
        documentAdapter = DocumentAdapter(
            onFolderClick = { folder ->
                folderNavHelper.navigateInto(folder)
            },
            onFileClick = { file ->
                val intent = Intent(this, EditorActivity::class.java).apply {
                    putExtra(EditorActivity.EXTRA_FILE_ID, file.id)
                    putExtra(EditorActivity.EXTRA_FILE_NAME, file.name)
                }
                startActivity(intent)
            },
            onItemLongClick = { item -> showItemActions(item) }
        )

        binding.rvDocuments.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = documentAdapter
        }
    }

    /** Initialize the drawer with dynamic sections via RecyclerView */
    private fun setupDrawer() {
        drawerAdapter = DrawerAdapter { menuItem ->
            when (menuItem.id) {
                "nav_my_documents" -> {
                    // Navigate to root documents
                    folderNavHelper.navigateToRoot()
                    binding.drawerLayout.closeDrawers()
                }
                "nav_logout" -> {
                    // 저장된 토큰을 모두 지우고 로그인 화면으로 복귀.
                    // CLEAR_TASK + NEW_TASK로 백 스택을 비워 뒤로가기로 메인에 돌아오지 못하게 한다.
                    TokenManager.clear(this)
                    val i = Intent(this, GoogleLoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(i)
                    finish()
                }
                else -> {
                    Toast.makeText(this, "${menuItem.title} clicked", Toast.LENGTH_SHORT).show()
                    binding.drawerLayout.closeDrawers()
                }
            }
        }

        binding.rvDrawerMenu.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = drawerAdapter
        }

        // Load dynamic drawer sections
        val sections = SampleDataRepository.getDrawerSections()
        drawerAdapter.updateSections(sections)
    }

    /** Set up folder navigation helper with callbacks to update the UI */
    private fun setupFolderNavigation() {
        folderNavHelper = FolderNavigationHelper(
            scope = lifecycleScope
        )

        folderNavHelper.onLoadingChanged = { loading ->
            if (loading) LoadingIndicator.show(this) else LoadingIndicator.hide(this)
        }

        folderNavHelper.onError = { throwable ->
            Log.w(TAG, "folder navigation failed", throwable)
            Toast.makeText(
                this,
                "폴더를 불러오지 못했습니다: ${throwable.message ?: "알 수 없는 오류"}",
                Toast.LENGTH_SHORT
            ).show()
        }

        folderNavHelper.onNavigationChanged = { items, pathDisplay, isRoot ->
            // Update the document list
            documentAdapter.updateItems(items)

            if (isRoot) {
                // Show "My Documents" title, hide navigation bar
                binding.tvSectionTitle.visibility = View.VISIBLE
                binding.layoutNavBar.visibility = View.GONE
            } else {
                // Hide title, show navigation bar with path
                binding.tvSectionTitle.visibility = View.GONE
                binding.layoutNavBar.visibility = View.VISIBLE
                binding.tvCurrentPath.text = pathDisplay
            }
        }

        // Back button handler
        binding.btnBack.setOnClickListener {
            folderNavHelper.navigateBack()
        }

        // 진입 대상 폴더가 전달되면 해당 폴더로 바로 들어가고, 아니면 루트에서 시작.
        val initialFolderId = intent.getStringExtra(EXTRA_FOLDER_ID)
        val initialFolderName = intent.getStringExtra(EXTRA_FOLDER_NAME)
        if (!initialFolderId.isNullOrBlank()) {
            folderNavHelper.navigateInto(
                kr.co.fixlog.model.FileItem(
                    id = initialFolderId,
                    name = initialFolderName.orEmpty(),
                    type = FileType.FOLDER,
                    date = ""
                )
            )
        } else {
            folderNavHelper.navigateToRoot()
        }

        // Modern back press handling for drawer close and folder navigation
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.drawerLayout.isDrawerOpen(GravityCompat.START) -> {
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                    }
                    folderNavHelper.navigateBack() -> {
                        // Handled by navigation helper
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    /**
     * "새 폴더" 생성 다이얼로그. 입력값으로 POST /api/folders 호출.
     * 성공 시 현재 위치를 reload해서 새 폴더가 즉시 목록에 보이도록 한다.
     */
    private fun showCreateFolderDialog() {
        val input = EditText(this).apply {
            hint = "폴더 이름"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("새 폴더")
            .setView(input)
            .setPositiveButton("만들기") { dialog, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(this, "폴더 이름을 입력하세요", Toast.LENGTH_SHORT).show()
                } else {
                    createFolder(name)
                }
                dialog.dismiss()
            }
            .setNegativeButton("취소") { dialog, _ -> dialog.cancel() }
            .show()
    }

    private fun createFolder(name: String) {
        val request = FolderRequest(
            parentId = folderNavHelper.currentFolderId(),
            folderName = name
        )
        lifecycleScope.launch {
            runCatching { FolderApi.createFolder(request) }
                .onSuccess {
                    Toast.makeText(this@MainActivity, "폴더가 생성되었습니다", Toast.LENGTH_SHORT).show()
                    folderNavHelper.reload()
                }
                .onFailure { e ->
                    Log.w(TAG, "createFolder failed", e)
                    Log.e("test", "folder create error : ${e.message}")
                    Toast.makeText(
                        this@MainActivity,
                        "폴더 생성 실패: ${e.message ?: "알 수 없는 오류"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }

    // ---------------------------------------------------------------------
    // 폴더/문서 CRUD 액션 (목록 아이템 롱클릭 → 컨텍스트 메뉴)
    // ---------------------------------------------------------------------

    /** 롱클릭한 아이템의 타입에 따라 사용 가능한 액션 메뉴를 띄운다. */
    private fun showItemActions(item: kr.co.fixlog.model.FileItem) {
        val isFolder = item.type == FileType.FOLDER
        // 라벨과 실행을 순서대로 매칭한다.
        val actions = buildList<Pair<String, () -> Unit>> {
            add("이름 변경" to { showRenameDialog(item) })
            if (!isFolder) add("복제" to { duplicateDocument(item) })
            add("이동" to { showMovePicker(item) })
            add("삭제" to { confirmDelete(item) })
        }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun showRenameDialog(item: kr.co.fixlog.model.FileItem) {
        val input = EditText(this).apply {
            setText(item.name)
            setSelection(item.name.length)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("이름 변경")
            .setView(input)
            .setPositiveButton("변경") { dialog, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                when {
                    name.isBlank() -> Toast.makeText(this, "이름을 입력하세요", Toast.LENGTH_SHORT).show()
                    name == item.name -> Unit
                    else -> rename(item, name)
                }
                dialog.dismiss()
            }
            .setNegativeButton("취소") { d, _ -> d.cancel() }
            .show()
    }

    private fun rename(item: kr.co.fixlog.model.FileItem, newName: String) {
        runApiAction("이름을 변경했습니다", "이름 변경 실패") {
            if (item.type == FileType.FOLDER) {
                // updateFolder는 이름만 변경(부모/순서는 별도 API). parentId는 현재 값 유지.
                FolderApi.updateFolder(item.id, FolderRequest(parentId = item.parentId, folderName = newName))
            } else {
                DocumentApi.updateTitle(item.id, newName)
            }
        }
    }

    private fun duplicateDocument(item: kr.co.fixlog.model.FileItem) {
        runApiAction("문서를 복제했습니다", "복제 실패") {
            DocumentApi.duplicate(item.id)
        }
    }

    private fun confirmDelete(item: kr.co.fixlog.model.FileItem) {
        val msg = if (item.type == FileType.FOLDER) {
            "'${item.name}' 폴더를 삭제할까요?\n하위 폴더와 문서도 함께 삭제됩니다."
        } else {
            "'${item.name}' 문서를 삭제할까요?"
        }
        AlertDialog.Builder(this)
            .setTitle("삭제")
            .setMessage(msg)
            .setPositiveButton("삭제") { dialog, _ ->
                runApiAction("삭제했습니다", "삭제 실패") {
                    if (item.type == FileType.FOLDER) FolderApi.deleteFolder(item.id)
                    else DocumentApi.delete(item.id)
                }
                dialog.dismiss()
            }
            .setNegativeButton("취소") { d, _ -> d.cancel() }
            .show()
    }

    /**
     * 이동 대상 폴더 선택. 폴더 트리를 평탄화해 목록으로 보여준다.
     * - 첫 항목은 "루트(최상위)".
     * - 폴더 이동 시 자기 자신과 그 하위 폴더는 대상에서 제외(순환 방지).
     */
    private fun showMovePicker(item: kr.co.fixlog.model.FileItem) {
        lifecycleScope.launch {
            val tree = runCatching { FolderApi.getFolderTree() }.getOrElse { e ->
                Log.w(TAG, "폴더 트리 조회 실패", e)
                Toast.makeText(this@MainActivity, "폴더 목록을 불러오지 못했습니다", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val excluded = if (item.type == FileType.FOLDER) collectSubtreeIds(tree, item.id) else emptySet()
            val candidates = mutableListOf<Pair<String?, String>>() // (folderId, 표시명)
            candidates.add(null to "루트(최상위)")
            flattenTree(tree, 0, excluded, candidates)

            val labels = candidates.map { it.second }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("이동할 위치 선택")
                .setItems(labels) { _, which ->
                    val destId = candidates[which].first
                    move(item, destId)
                }
                .show()
        }
    }

    private fun move(item: kr.co.fixlog.model.FileItem, destFolderId: String?) {
        if (item.type == FileType.FOLDER && destFolderId == item.id) return
        runApiAction("이동했습니다", "이동 실패") {
            if (item.type == FileType.FOLDER) FolderApi.moveFolder(item.id, destFolderId)
            else DocumentApi.move(item.id, destFolderId)
        }
    }

    /** 트리를 들여쓰기 문자열과 함께 (id, 표시명) 목록으로 평탄화. excluded에 속한 노드는 자신·하위 모두 건너뛴다. */
    private fun flattenTree(
        nodes: List<FolderTreeDto>,
        depth: Int,
        excluded: Set<String>,
        out: MutableList<Pair<String?, String>>
    ) {
        for (node in nodes) {
            if (node.folderId in excluded) continue
            out.add(node.folderId to ("  ".repeat(depth) + node.folderName))
            flattenTree(node.children, depth + 1, excluded, out)
        }
    }

    /** 특정 폴더 id의 서브트리(자기 자신 포함) 모든 folderId를 수집. */
    private fun collectSubtreeIds(nodes: List<FolderTreeDto>, targetId: String): Set<String> {
        fun subtreeIds(node: FolderTreeDto): Set<String> =
            buildSet {
                add(node.folderId)
                node.children.forEach { addAll(subtreeIds(it)) }
            }
        fun find(list: List<FolderTreeDto>): FolderTreeDto? {
            for (n in list) {
                if (n.folderId == targetId) return n
                find(n.children)?.let { return it }
            }
            return null
        }
        return find(nodes)?.let { subtreeIds(it) } ?: setOf(targetId)
    }

    /** 공통: 백그라운드 API 호출 → 성공/실패 토스트 + 성공 시 현재 목록 reload. */
    private fun runApiAction(successMsg: String, failPrefix: String, block: suspend () -> Unit) {
        lifecycleScope.launch {
            runCatching { block() }
                .onSuccess {
                    Toast.makeText(this@MainActivity, successMsg, Toast.LENGTH_SHORT).show()
                    folderNavHelper.reload()
                }
                .onFailure { e ->
                    Log.w(TAG, "$failPrefix: ${e.message}", e)
                    Toast.makeText(
                        this@MainActivity,
                        "$failPrefix: ${e.message ?: "알 수 없는 오류"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        /** 특정 폴더로 바로 진입하도록 넘기는 익스트라(카드 리스트에서 폴더 탭 시 사용). */
        const val EXTRA_FOLDER_ID = "folder_id"
        const val EXTRA_FOLDER_NAME = "folder_name"
    }
}