package kr.co.fixlog.activity

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.adapter.TrashAdapter
import kr.co.fixlog.data.remote.FolderApi
import kr.co.fixlog.data.remote.TrashApi
import kr.co.fixlog.data.remote.dto.TrashItemDto
import kr.co.fixlog.databinding.ActivityTrashBinding
import kr.co.fixlog.util.BottomNav
import kr.co.fixlog.util.LoadingIndicator
import kr.co.fixlog.util.CoachMark
import kr.co.fixlog.util.FolderPickerDialog

/**
 * 휴지통(/trash) 화면. 삭제된 폴더·문서를 복원하거나 영구 삭제한다.
 *  - 목록: GET /api/trash
 *  - 복원: POST /api/trash/{type}/{id}/restore (상위 폴더도 휴지통인 항목은 복원 위치를 골라 targetFolderId 로 보냄)
 *  - 영구삭제: DELETE /api/trash/{type}/{id} (확인 팝업)
 */
class TrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrashBinding
    private lateinit var adapter: TrashAdapter

    /** 코치마크 튜토리얼 중이면 서버 대신 샘플 목록을 보여준다. 끝나면 실제 목록을 로드한다. */
    private var tutorial = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityTrashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        adapter = TrashAdapter(
            onRestore = { item -> restore(item) },
            onPurge = { item -> confirmPurge(item) }
        )
        binding.rvTrash.apply {
            layoutManager = LinearLayoutManager(this@TrashActivity)
            adapter = this@TrashActivity.adapter
        }

        // 아래로 당겨 새로고침: 전체 화면 로딩 대신 상단 스피너로 표시한다.
        binding.swipeRefresh.apply {
            setColorSchemeResources(R.color.editor_blue)
            setProgressBackgroundColorSchemeResource(R.color.surface)
            setOnRefreshListener { load(showOverlay = false) }
        }

        BottomNav.setup(this, binding.bottomNav, BottomNav.Tab.TRASH)
        tutorial = CoachMark.show(this, "trash",
            CoachMark.Step(R.string.coach_trash_list) { listOf(binding.rvTrash.getChildAt(0)) },
            CoachMark.Step(R.string.coach_trash_restore) { listOf(binding.rvTrash.getChildAt(0)?.findViewById(R.id.btn_restore)) },
            CoachMark.Step(R.string.coach_trash_purge) { listOf(binding.rvTrash.getChildAt(0)?.findViewById(R.id.btn_purge)) }
        ) {
            tutorial = false
            load()
        }
    }

    override fun onResume() {
        super.onResume()
        if (tutorial) showSamples() else load()
    }

    /** 튜토리얼용 샘플 목록. 서버에 저장되지 않으며 화면에만 표시한다. */
    private fun showSamples() {
        val now = java.time.LocalDate.now().toString()
        render(listOf(
            TrashItemDto("FOLDER", "sample-folder", getString(R.string.sample_folder), null, now),
            TrashItemDto("DOCUMENT", "sample-doc-1", getString(R.string.sample_doc_deploy), null, now)
        ))
    }

    private fun load(showOverlay: Boolean = true) {
        if (showOverlay) LoadingIndicator.show(this)
        lifecycleScope.launch {
            runCatching { TrashApi.list() }
                .onSuccess { items -> render(items) }
                .onFailure { e ->
                    Log.w(TAG, "휴지통 조회 실패", e)
                    render(emptyList())
                    toast(getString(R.string.trash_load_failed))
                }
            if (showOverlay) LoadingIndicator.hide(this@TrashActivity)
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun render(items: List<TrashItemDto>) {
        adapter.update(items)
        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun restore(item: TrashItemDto) {
        // 상위 폴더가 휴지통이라 원래 위치가 없으면(문서·폴더 모두): 폴더 선택 팝업으로 복원 위치를 받는다.
        if (item.trashedParentId != null) {
            lifecycleScope.launch {
                val tree = runCatching { FolderApi.getFolderTree() }.getOrElse { e ->
                    Log.w(TAG, "폴더 트리 조회 실패", e)
                    toast(getString(R.string.actions_load_folders_failed))
                    return@launch
                }
                FolderPickerDialog.show(this@TrashActivity, getString(R.string.trash_select_restore_folder), tree) { destId ->
                    restore(item, destId)
                }
            }
            return
        }
        restore(item, null)
    }

    private fun restore(item: TrashItemDto, targetFolderId: String?) {
        lifecycleScope.launch {
            runCatching { TrashApi.restore(item.resourceType, item.resourceId, targetFolderId) }
                .onSuccess {
                    toast(getString(R.string.trash_restored))
                    load()
                }
                .onFailure { e ->
                    Log.w(TAG, "복원 실패", e)
                    toast(getString(R.string.trash_restore_failed, e.message ?: getString(R.string.common_unknown_error)))
                }
        }
    }

    private fun confirmPurge(item: TrashItemDto) {
        val name = item.name?.ifBlank { null } ?: getString(R.string.trash_this_item)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.trash_purge))
            .setMessage(getString(R.string.trash_purge_confirm, name) +
                if (item.resourceType.equals("FOLDER", ignoreCase = true)) getString(R.string.trash_purge_folder_extra) else "")
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.trash_purge)) { _, _ -> purge(item) }
            .show()
    }

    private fun purge(item: TrashItemDto) {
        lifecycleScope.launch {
            runCatching { TrashApi.purge(item.resourceType, item.resourceId) }
                .onSuccess {
                    toast(getString(R.string.trash_purged))
                    load()
                }
                .onFailure { e ->
                    Log.w(TAG, "영구 삭제 실패", e)
                    toast(getString(R.string.trash_purge_failed, e.message ?: getString(R.string.common_unknown_error)))
                }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "TrashActivity"
    }
}
