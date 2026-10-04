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
import kr.co.fixlog.data.remote.TrashApi
import kr.co.fixlog.data.remote.dto.TrashItemDto
import kr.co.fixlog.databinding.ActivityTrashBinding
import kr.co.fixlog.util.BottomNav
import kr.co.fixlog.util.LoadingIndicator

/**
 * 휴지통(/trash) 화면. 삭제된 폴더·문서를 복원하거나 영구 삭제한다.
 *  - 목록: GET /api/trash
 *  - 복원: POST /api/trash/{type}/{id}/restore
 *  - 영구삭제: DELETE /api/trash/{type}/{id} (확인 팝업)
 */
class TrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrashBinding
    private lateinit var adapter: TrashAdapter

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

        BottomNav.setup(this, binding.bottomNav, BottomNav.Tab.TRASH)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        LoadingIndicator.show(this)
        lifecycleScope.launch {
            runCatching { TrashApi.list() }
                .onSuccess { items -> render(items) }
                .onFailure { e ->
                    Log.w(TAG, "휴지통 조회 실패", e)
                    render(emptyList())
                    toast(getString(R.string.trash_load_failed))
                }
            LoadingIndicator.hide(this@TrashActivity)
        }
    }

    private fun render(items: List<TrashItemDto>) {
        adapter.update(items)
        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun restore(item: TrashItemDto) {
        lifecycleScope.launch {
            runCatching { TrashApi.restore(item.resourceType, item.resourceId) }
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
            .setMessage(getString(R.string.trash_purge_confirm, name))
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
