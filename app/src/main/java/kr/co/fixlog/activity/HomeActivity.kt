package kr.co.fixlog.activity

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.data.remote.AuthApi
import kr.co.fixlog.data.remote.DocumentApi
import kr.co.fixlog.data.remote.FolderApi
import kr.co.fixlog.data.remote.dto.DocumentDto
import kr.co.fixlog.data.remote.dto.FolderDto
import kr.co.fixlog.data.remote.dto.FolderRequest
import kr.co.fixlog.databinding.ActivityHomeBinding
import kr.co.fixlog.databinding.ItemDocCardBinding
import kr.co.fixlog.util.AllDialog
import kr.co.fixlog.util.BottomNav
import kr.co.fixlog.util.DocumentActionsHelper
import kr.co.fixlog.util.TokenManager
import kr.co.fixlog.util.launchWithLoading

/**
 * 홈(/workspace) 화면. 최근 문서(GET /api/documents)를 상단 카드에 채우고,
 * New/빠른동작을 실제 문서·폴더 생성 API에 연결한다. AI 검색은 서버 미구현이라 검색 화면으로만 진입.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var recentCards: List<ItemDocCardBinding>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        recentCards = listOf(binding.rec1, binding.rec2)

        // 상단 New / 빠른 동작 / AI 검색 진입 / 추천칩
        binding.btnNew.setOnClickListener { createDocumentAndOpen() }
        binding.qaNewDoc.setOnClickListener { createDocumentAndOpen() }
        binding.qaNewFolder.setOnClickListener { showCreateFolderDialog() }
        binding.qaImport.setOnClickListener { toast("문서 가져오기는 준비 중입니다") }
        binding.aiSearchBar.setOnClickListener { openSearch() }
        listOf(binding.chip1, binding.chip2, binding.chip3, binding.chip4)
            .forEach { it.setOnClickListener { openSearch() } }

        BottomNav.setup(this, binding.bottomNav, BottomNav.Tab.HOME) { AllDialog.show(this) }

        verifyLogin()
    }

    /**
     * 앱 진입 시 로그인 상태를 확인해 toast로 알린다.
     * 저장된 토큰이 없으면 바로 안내하고, 있으면 GET /auth/token으로 실제 유효성까지 검증한다.
     */
    private fun verifyLogin() {
        if (!TokenManager.isLoggedIn(this)) {
            toast("로그인이 필요합니다")
            return
        }
        lifecycleScope.launch {
            runCatching { AuthApi.session() }
                .onSuccess {
                    // 로그인 성공 시에는 별도 안내 없이 조용히 통과(toast 제거).
                    Log.d(TAG, "세션 확인됨")
                }
                .onFailure { e ->
                    Log.w(TAG, "세션 확인 실패", e)
                    toast("로그인 확인 실패: 다시 로그인해 주세요")
                }
        }
    }

    /** 화면 복귀 시마다 최신 콘텐츠 갱신. */
    override fun onResume() {
        super.onResume()
        loadRootContents()
    }

    /** 루트(My Documents) 콘텐츠(폴더+문서)를 상단 카드에 채운다. 폴더 먼저, 그 다음 문서. */
    private fun loadRootContents() {
        launchWithLoading {
            runCatching { FolderApi.getRootContents() }
                .onSuccess { contents -> bindEntries(contents.folders, contents.documents) }
                .onFailure { e ->
                    Log.w(TAG, "콘텐츠 조회 실패", e)
                    bindEntries(emptyList(), emptyList())
                }
        }
    }

    private fun bindEntries(folders: List<FolderDto>, docs: List<DocumentDto>) {
        var i = 0
        for (folder in folders) {
            if (i >= recentCards.size) break
            bindFolder(recentCards[i], folder)
            recentCards[i].root.visibility = View.VISIBLE
            i++
        }
        for (doc in docs) {
            if (i >= recentCards.size) break
            bindDocument(recentCards[i], doc)
            recentCards[i].root.visibility = View.VISIBLE
            i++
        }
        while (i < recentCards.size) {
            recentCards[i].root.visibility = View.GONE
            i++
        }
    }

    /** 폴더 카드: 폴더 아이콘 + 이름만. 탭 시 폴더 브라우저로 진입. */
    private fun bindFolder(card: ItemDocCardBinding, folder: FolderDto) {
        card.docIcon.setImageResource(R.drawable.ic_folder)
        card.docTitle.text = folder.folderName.ifBlank { "Untitled folder" }
        card.docDesc.visibility = View.GONE
        card.docStatus.visibility = View.GONE
        card.docMeta.visibility = View.GONE
        card.root.setOnClickListener {
            // 폴더 탐색은 Documents 화면의 브라우저에서 처리(해당 폴더로 바로 진입).
            startActivity(Intent(this, DocumentsActivity::class.java).apply {
                putExtra(DocumentsActivity.EXTRA_FOLDER_ID, folder.folderId)
                putExtra(DocumentsActivity.EXTRA_FOLDER_NAME, folder.folderName)
            })
        }
        card.root.setOnLongClickListener(null)
    }

    /** 문서 카드: 아이콘/제목/설명/작성자. 탭 시 에디터, 롱클릭 시 CRUD 메뉴. */
    private fun bindDocument(card: ItemDocCardBinding, doc: DocumentDto) {
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
                loadRootContents()
            }
            true
        }
    }

    /** 최근 문서 메타: 작성자 식별자 대신 수정 날짜(없으면 생성 날짜, YYYY-MM-DD)를 표시. */
    private fun buildMeta(doc: DocumentDto): String =
        (doc.updateTime ?: doc.createTime)?.take(10).orEmpty()

    /** 새 문서를 서버에 생성(루트 직속)한 뒤 에디터로 진입. */
    private fun createDocumentAndOpen() {
        lifecycleScope.launch {
            runCatching { DocumentApi.create(folderId = null, title = "Untitled") }
                .onSuccess { doc -> openEditor(doc.documentId, doc.title) }
                .onFailure { e ->
                    Log.w(TAG, "문서 생성 실패", e)
                    toast("문서 생성 실패: ${e.message ?: "알 수 없는 오류"}")
                }
        }
    }

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
                if (name.isBlank()) toast("폴더 이름을 입력하세요") else createFolder(name)
                dialog.dismiss()
            }
            .setNegativeButton("취소") { dialog, _ -> dialog.cancel() }
            .show()
    }

    private fun createFolder(name: String) {
        lifecycleScope.launch {
            runCatching { FolderApi.createFolder(FolderRequest(parentId = null, folderName = name)) }
                .onSuccess { toast("폴더가 생성되었습니다") }
                .onFailure { e ->
                    Log.w(TAG, "폴더 생성 실패", e)
                    toast("폴더 생성 실패: ${e.message ?: "알 수 없는 오류"}")
                }
        }
    }

    private fun openEditor(documentId: String, title: String) {
        startActivity(Intent(this, EditorActivity::class.java).apply {
            putExtra(EditorActivity.EXTRA_FILE_ID, documentId)
            putExtra(EditorActivity.EXTRA_FILE_NAME, title)
        })
    }

    private fun openSearch() {
        startActivity(Intent(this, SearchActivity::class.java))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "HomeActivity"
    }
}
