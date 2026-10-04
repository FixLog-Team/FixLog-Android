package kr.co.fixlog.activity

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.adapter.DocumentAdapter
import kr.co.fixlog.data.remote.AuthApi
import kr.co.fixlog.data.remote.ShareApi
import kr.co.fixlog.data.remote.WorkspaceApi
import kr.co.fixlog.data.remote.dto.SharedResourcesDto
import kr.co.fixlog.data.remote.dto.WorkspaceDto
import kr.co.fixlog.databinding.ActivitySettingsBinding
import kr.co.fixlog.model.FileItem
import kr.co.fixlog.model.FileType
import kr.co.fixlog.util.BottomNav
import kr.co.fixlog.util.TokenManager
import kr.co.fixlog.util.WorkspaceManager
import org.json.JSONObject

/**
 * 설정 화면. 하단 네비게이션의 "설정" 탭에서 진입한다.
 *
 * 기능:
 *  - 현재 로그인 계정 정보(이름/이메일)를 서버 세션(GET /auth/token)에서 조회해 표시.
 *  - 워크스페이스 관리: 현재 워크스페이스 표시, 전환, 추가(생성), 나가기/삭제.
 *    전환은 [WorkspaceManager]에 선택 id를 저장하기만 하면 되고(§1.2), 이후 요청에
 *    [kr.co.fixlog.data.remote.AuthInterceptor]가 X-Workspace-Id 헤더를 자동 부착한다.
 *  - 로그아웃: 서버에 로그아웃 API가 없어 클라이언트에서 토큰/워크스페이스 선택을 삭제하고 로그인 화면으로 이동.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** 마지막으로 조회한 워크스페이스 목록(전환/나가기 판단용). */
    private var workspaces: List<WorkspaceDto> = emptyList()

    /** 공유 탭 트리 어댑터(내가 공유한/공유받은 공통). */
    private lateinit var sharedAdapter: kr.co.fixlog.adapter.SharedTreeAdapter
    /** 0 = 내가 공유한(shared-by-me), 1 = 공유받은(shared-with-me). */
    private var sharedTab = 0
    /** 소유자 userId → 이름 매핑(공유 메타 표시용). */
    private var ownerNames: Map<String, String> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        BottomNav.setup(this, binding.bottomNav, BottomNav.Tab.SETTINGS)

        binding.btnLogout.setOnClickListener { confirmLogout() }
        binding.cardWorkspace.setOnClickListener { showSwitchWorkspaceDialog() }
        binding.btnAddWs.setOnClickListener { showAddWorkspaceDialog() }
        binding.btnLeaveWs.setOnClickListener { confirmLeaveOrDelete() }

        setupSharedTabs()
        loadAccount()
    }

    override fun onResume() {
        super.onResume()
        loadWorkspaces()
        loadShared()
    }

    // ---------------------------------------------------------------------
    // 계정
    // ---------------------------------------------------------------------

    /**
     * 서버 세션(GET /auth/token)에서 계정 정보를 가져와 표시한다.
     * 실패(비로그인/네트워크)해도 로그아웃은 가능하도록 화면은 유지하고 안내 문구만 바꾼다.
     */
    private fun loadAccount() {
        lifecycleScope.launch {
            runCatching { AuthApi.session() }
                .onSuccess { raw -> renderAccount(raw) }
                .onFailure { e ->
                    Log.d(TAG, "세션 조회 실패: ${e.message}")
                    binding.tvUserName.text = getString(R.string.settings_account_load_failed)
                    binding.tvUserEmail.text = ""
                }
        }
    }

    private fun renderAccount(rawResult: String) {
        val obj = runCatching { JSONObject(rawResult) }.getOrNull()
        val name = obj?.optString("userName").orEmptyIfBlank()
        val email = obj?.optString("email").orEmptyIfBlank()

        binding.tvUserName.text = name ?: getString(R.string.settings_no_name)
        binding.tvUserEmail.text = email ?: ""
        binding.tvAvatar.text = avatarInitial(name ?: email ?: "")
    }

    /** 이름/이메일에서 아바타 이니셜(최대 2글자) 생성. */
    private fun avatarInitial(source: String): String {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return "·"
        val parts = trimmed.split(Regex("\\s+"))
        val initials = if (parts.size >= 2) {
            "${parts[0].first()}${parts[1].first()}"
        } else {
            trimmed.take(2)
        }
        return initials.uppercase()
    }

    // ---------------------------------------------------------------------
    // 워크스페이스
    // ---------------------------------------------------------------------

    /** 워크스페이스 목록을 조회해 현재 선택 상태를 렌더한다. 선택 id가 더 이상 목록에 없으면 개인 WS로 되돌린다. */
    private fun loadWorkspaces() {
        lifecycleScope.launch {
            runCatching { WorkspaceApi.list() }
                .onSuccess { list ->
                    workspaces = list
                    // 선택된 협업 WS가 목록에서 사라졌으면(나가기/삭제됨) 개인 WS로 복귀.
                    val selectedId = WorkspaceManager.getSelectedId(this@SettingsActivity)
                    if (selectedId != null && list.none { it.workspaceId == selectedId }) {
                        WorkspaceManager.selectPersonal(this@SettingsActivity)
                    }
                    renderCurrentWorkspace()
                }
                .onFailure { e ->
                    Log.w(TAG, "워크스페이스 목록 조회 실패", e)
                    workspaces = emptyList()
                    renderCurrentWorkspace()
                }
        }
    }

    /** 현재 선택된 워크스페이스 DTO(협업). 개인/미선택이면 null. */
    private fun currentWorkspace(): WorkspaceDto? {
        val id = WorkspaceManager.getSelectedId(this) ?: return null
        return workspaces.firstOrNull { it.workspaceId == id }
    }

    private fun renderCurrentWorkspace() {
        val current = currentWorkspace()
        if (current == null) {
            // 개인 워크스페이스(헤더 미부착). 나가기/삭제 불가.
            binding.tvWsName.text = getString(R.string.ws_personal)
            binding.tvWsRole.text = getString(R.string.ws_personal_role)
            binding.tvWsRole.visibility = View.VISIBLE
            binding.btnLeaveWs.text = getString(R.string.ws_leave)
        } else {
            binding.tvWsName.text = current.displayName
            binding.tvWsRole.text = roleLabel(current.role)
            binding.tvWsRole.visibility = View.VISIBLE
            // 소유자 → 삭제, 그 외 구성원 → 나가기.
            binding.btnLeaveWs.text = if (current.isOwner) getString(R.string.ws_delete) else getString(R.string.ws_leave)
        }
    }

    // ---------------------------------------------------------------------
    // 공유 탭(내가 공유한 / 공유받은)
    // ---------------------------------------------------------------------

    private fun setupSharedTabs() {
        sharedAdapter = kr.co.fixlog.adapter.SharedTreeAdapter(
            scope = lifecycleScope,
            resolveOwner = { id -> kr.co.fixlog.util.OwnerNames.resolve(ownerNames, id) },
            onFileClick = { file ->
                startActivity(Intent(this, EditorActivity::class.java).apply {
                    putExtra(EditorActivity.EXTRA_FILE_ID, file.id)
                    putExtra(EditorActivity.EXTRA_FILE_NAME, file.name)
                })
            }
        )
        binding.rvShared.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = sharedAdapter
            isNestedScrollingEnabled = false
        }
        binding.tabShared.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                sharedTab = tab.position
                loadShared()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    /** 현재 선택된 탭(내가 공유한/공유받은) 목록을 로드해 표시한다. */
    private fun loadShared() {
        showSharedSkeleton()
        lifecycleScope.launch {
            val tab = sharedTab
            // 소유자 id → 이름 매핑(구성원+세션). 폴더/문서 메타에서 id 대신 이름을 쓰기 위함.
            ownerNames = runCatching { kr.co.fixlog.util.OwnerNames.load(this@SettingsActivity) }.getOrDefault(emptyMap())
            val result: SharedResourcesDto = runCatching {
                if (tab == 0) ShareApi.sharedByMe() else ShareApi.sharedWithMe()
            }.getOrDefault(SharedResourcesDto())
            val items = result.folders.map {
                FileItem(it.folderId, it.folderName, FileType.FOLDER, it.updateTime?.take(10).orEmpty(),
                    owner = it.createUser)
            } + result.documents.map {
                // 문서 상세/공유 응답에는 작성자 이름(createUserName)이 포함되므로 우선 사용.
                FileItem(it.documentId, it.title, FileType.FILE, (it.updateTime ?: it.createTime)?.take(10).orEmpty(),
                    owner = it.createUserName ?: it.createUser,
                    labels = it.labels?.mapNotNull { l -> l.labelName?.takeIf(String::isNotBlank) }.orEmpty())
            }
            if (tab != sharedTab) return@launch // 그 사이 탭이 바뀌었으면 무시
            hideSharedSkeleton()
            sharedAdapter.setRoots(items)
            binding.tvSharedEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** 공유 리스트 로딩 중 skeleton 표시(반짝임) + 실제 리스트/빈 문구 숨김. */
    private fun showSharedSkeleton() {
        binding.llSharedSkeleton.visibility = View.VISIBLE
        binding.rvShared.visibility = View.INVISIBLE
        binding.tvSharedEmpty.visibility = View.GONE
        kr.co.fixlog.util.Shimmer.start(binding.llSharedSkeleton)
    }

    private fun hideSharedSkeleton() {
        kr.co.fixlog.util.Shimmer.stop(binding.llSharedSkeleton)
        binding.llSharedSkeleton.visibility = View.GONE
        binding.rvShared.visibility = View.VISIBLE
    }

    private fun roleLabel(role: String?): String = when (role?.uppercase()) {
        "OWNER" -> getString(R.string.ws_role_owner)
        "ADMIN" -> getString(R.string.ws_role_admin)
        "MEMBER" -> getString(R.string.ws_role_member)
        else -> role ?: getString(R.string.ws_role_member)
    }

    /**
     * 전환 다이얼로그: 개인 + 조회된 협업 WS 목록에서 선택.
     * 각 행 우측에 role 을 표시하고, 현재 선택 항목은 체크로 강조한다.
     */
    private fun showSwitchWorkspaceDialog() {
        if (workspaces.isEmpty()) {
            toast(getString(R.string.ws_loading))
            loadWorkspaces()
            return
        }

        val currentId = WorkspaceManager.getSelectedId(this)
        // (id, 표시명, role표시) — id==null 이면 개인 워크스페이스.
        val hasPersonalEntry = workspaces.any { it.isPersonal }
        val entries = buildList<Triple<String?, String, String>> {
            if (!hasPersonalEntry) add(Triple(null, getString(R.string.ws_personal), getString(R.string.ws_personal_role)))
            workspaces.forEach { ws ->
                val id = if (ws.isPersonal) null else ws.workspaceId
                val role = if (ws.isPersonal) getString(R.string.ws_personal_role) else roleLabel(ws.role)
                add(Triple(id, ws.displayName, role))
            }
        }

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(container) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.ws_switch))
            .setView(scroll)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .create()

        entries.forEach { (id, name, role) ->
            val row = layoutInflater.inflate(R.layout.item_workspace_row, container, false)
            row.findViewById<TextView>(R.id.tv_ws_row_name).text = name
            row.findViewById<TextView>(R.id.tv_ws_row_role).text = role
            val isCurrent = id == currentId || (id == null && currentId == null)
            row.findViewById<View>(R.id.iv_check).visibility = if (isCurrent) View.VISIBLE else View.INVISIBLE
            row.setOnClickListener {
                if (id == null) {
                    WorkspaceManager.selectPersonal(this, name)
                } else {
                    val ws = workspaces.firstOrNull { it.workspaceId == id }
                    WorkspaceManager.select(this, id, ws?.displayName ?: name, ws?.role)
                }
                renderCurrentWorkspace()
                toast(getString(R.string.ws_switched, name))
                dialog.dismiss()
            }
            container.addView(row)
        }

        dialog.show()
    }

    /** 추가 다이얼로그: 이름 입력 → 생성 → 목록 갱신 후 새 워크스페이스로 전환. */
    private fun showAddWorkspaceDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.ws_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ws_add))
            .setView(input)
            .setPositiveButton(getString(R.string.common_create)) { dialog, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) toast(getString(R.string.ws_enter_name)) else createWorkspace(name)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.common_cancel)) { d, _ -> d.cancel() }
            .show()
    }

    private fun createWorkspace(name: String) {
        lifecycleScope.launch {
            runCatching { WorkspaceApi.create(name) }
                .onSuccess { ws ->
                    toast(getString(R.string.ws_created))
                    // 개인 WS가 아니면 방금 만든 워크스페이스로 자동 전환.
                    if (!ws.isPersonal) {
                        WorkspaceManager.select(this@SettingsActivity, ws.workspaceId, ws.displayName, ws.role)
                    }
                    loadWorkspaces()
                }
                .onFailure { e ->
                    Log.w(TAG, "워크스페이스 생성 실패", e)
                    toast(getString(R.string.ws_create_failed, e.message ?: getString(R.string.common_unknown_error)))
                }
        }
    }

    /** 워크스페이스 나가기(구성원) / 삭제(소유자). 개인 워크스페이스는 불가 안내. */
    private fun confirmLeaveOrDelete() {
        val current = currentWorkspace() ?: run {
            toast(getString(R.string.ws_cannot_leave_personal))
            return
        }
        val isOwner = current.isOwner
        val title = if (isOwner) getString(R.string.ws_delete) else getString(R.string.ws_leave)
        val message = if (isOwner)
            getString(R.string.ws_delete_confirm, current.displayName)
        else
            getString(R.string.ws_leave_confirm, current.displayName)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(if (isOwner) getString(R.string.common_delete) else getString(R.string.common_leave)) { _, _ -> leaveOrDelete(current, isOwner) }
            .show()
    }

    private fun leaveOrDelete(ws: WorkspaceDto, isOwner: Boolean) {
        lifecycleScope.launch {
            runCatching {
                if (isOwner) WorkspaceApi.delete(ws.workspaceId) else WorkspaceApi.leave(ws.workspaceId)
            }
                .onSuccess {
                    // 개인 워크스페이스로 복귀 후 목록 재조회.
                    WorkspaceManager.selectPersonal(this@SettingsActivity)
                    toast(if (isOwner) getString(R.string.ws_deleted) else getString(R.string.ws_left))
                    loadWorkspaces()
                }
                .onFailure { e ->
                    Log.w(TAG, "워크스페이스 나가기/삭제 실패", e)
                    toast(getString(R.string.settings_action_failed, e.message ?: getString(R.string.common_unknown_error)))
                }
        }
    }

    // ---------------------------------------------------------------------
    // 로그아웃
    // ---------------------------------------------------------------------

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.common_logout))
            .setMessage(getString(R.string.settings_logout_confirm))
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_logout)) { _, _ -> logout() }
            .show()
    }

    /**
     * 클라이언트 토큰 + 워크스페이스 선택을 삭제하고 로그인 화면으로 되돌린다.
     * 백 스택 전체를 비워(NEW_TASK|CLEAR_TASK) 뒤로가기로 이전 화면에 복귀하지 못하게 한다.
     */
    private fun logout() {
        TokenManager.clear(this)
        WorkspaceManager.clear(this)
        val intent = Intent(this, GoogleLoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun String?.orEmptyIfBlank(): String? = this?.takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "SettingsActivity"
    }
}
