package kr.co.fixlog.util

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.data.remote.DocumentApi
import kr.co.fixlog.data.remote.FolderApi
import kr.co.fixlog.data.remote.dto.FolderRequest
import kr.co.fixlog.data.remote.dto.FolderTreeDto
import kr.co.fixlog.model.FileItem
import kr.co.fixlog.model.FileType

/**
 * 문서 트리 리스트(폴더+문서 공통)의 더보기(⋮) 액션 팝업.
 *
 * 제공 액션: 이름 변경 / 이동 / 삭제. (공유는 제외)
 *  - 이름 변경: 폴더 `PUT /api/folders/{id}`, 문서 `PATCH /api/documents/{id}/title`
 *  - 이동     : 트리에서 대상 폴더 선택 → 폴더 `PATCH /api/folders/{id}/move`, 문서 `PATCH /api/documents/{id}/move`
 *               (폴더 이동 시 자기 자신과 하위 폴더는 대상에서 제외해 순환 방지)
 *  - 삭제     : 휴지통으로 이동(웹 휴지통에서 복원 가능) 안내 후 `DELETE`
 */
object TreeItemActionsHelper {

    private const val TAG = "TreeItemActions"

    /**
     * @param onChanged 변경(이름/이동/삭제) 성공 후 호출 — 목록 새로고침 용도.
     */
    fun show(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        item: FileItem,
        onChanged: () -> Unit
    ) {
        val actions = buildList {
            add(ActionMenuDialog.Action(activity.getString(R.string.common_rename), R.drawable.ic_edit) { showRenameDialog(activity, scope, item, onChanged) })
            add(ActionMenuDialog.Action(activity.getString(R.string.common_move), R.drawable.ic_folder_move) { showMovePicker(activity, scope, item, onChanged) })
            add(ActionMenuDialog.Action(activity.getString(R.string.actions_share), R.drawable.ic_share) {
                val kind = if (item.type == FileType.FOLDER) "folder" else "document"
                ShareDialogHelper.show(activity, scope, kind, item.id, item.name)
            })
            add(ActionMenuDialog.Action(activity.getString(R.string.common_delete), R.drawable.ic_trash, danger = true) { confirmDelete(activity, scope, item, onChanged) })
        }
        val title = item.name.ifBlank { if (item.type == FileType.FOLDER) activity.getString(R.string.tree_folder) else activity.getString(R.string.tree_document) }
        val icon = if (item.type == FileType.FOLDER) R.drawable.ic_folder else R.drawable.ic_file
        ActionMenuDialog.show(activity, title, actions, icon)
    }

    private fun showRenameDialog(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        item: FileItem,
        onChanged: () -> Unit
    ) {
        val input = DialogInput(activity, text = item.name)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.common_rename))
            .setView(input.view)
            .setPositiveButton(activity.getString(R.string.actions_change)) { dialog, _ ->
                val name = input.value
                when {
                    name.isBlank() -> Toast.makeText(activity, activity.getString(R.string.actions_enter_name), Toast.LENGTH_SHORT).show()
                    name == item.name -> Unit
                    else -> runAction(activity, scope, activity.getString(R.string.actions_name_changed), activity.getString(R.string.actions_rename_failed), onChanged) {
                        if (item.type == FileType.FOLDER) {
                            FolderApi.updateFolder(item.id, FolderRequest(parentId = item.parentId, folderName = name))
                        } else {
                            DocumentApi.updateTitle(item.id, name)
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(activity.getString(R.string.common_cancel)) { d, _ -> d.cancel() }
            .create()
        input.showWithKeyboard(dialog)
    }

    private fun confirmDelete(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        item: FileItem,
        onChanged: () -> Unit
    ) {
        val target = item.name.ifBlank { if (item.type == FileType.FOLDER) activity.getString(R.string.tree_this_folder) else activity.getString(R.string.tree_this_document) }
        val extra = if (item.type == FileType.FOLDER) activity.getString(R.string.tree_delete_folder_extra) else ""
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.common_delete))
            .setMessage(activity.getString(R.string.tree_delete_confirm, target, extra))
            .setPositiveButton(activity.getString(R.string.common_delete)) { dialog, _ ->
                runAction(activity, scope, activity.getString(R.string.tree_moved_to_trash), activity.getString(R.string.actions_delete_failed), onChanged) {
                    if (item.type == FileType.FOLDER) FolderApi.deleteFolder(item.id)
                    else DocumentApi.delete(item.id)
                }
                dialog.dismiss()
            }
            .setNegativeButton(activity.getString(R.string.common_cancel)) { d, _ -> d.cancel() }
            .show()
    }

    /** 폴더 트리를 들여쓰기(트리 구조)로 보여주고 선택 위치로 이동한다. 첫 항목은 루트. */
    private fun showMovePicker(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        item: FileItem,
        onChanged: () -> Unit
    ) {
        scope.launch {
            val tree = runCatching { FolderApi.getFolderTree() }.getOrElse { e ->
                Log.w(TAG, "폴더 트리 조회 실패", e)
                Toast.makeText(activity, activity.getString(R.string.actions_load_folders_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 폴더 이동 시 자기 자신+하위는 대상에서 제외(순환 방지).
            val excluded = if (item.type == FileType.FOLDER) collectSubtreeIds(tree, item.id) else emptySet()
            FolderPickerDialog.show(activity, activity.getString(R.string.tree_select_move_folder), tree, excluded) { destId ->
                if (item.type == FileType.FOLDER && destId == item.id) return@show
                runAction(activity, scope, activity.getString(R.string.actions_moved), activity.getString(R.string.actions_move_failed), onChanged) {
                    if (item.type == FileType.FOLDER) FolderApi.moveFolder(item.id, destId)
                    else DocumentApi.move(item.id, destId)
                }
            }
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

    private fun runAction(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        successMsg: String,
        failPrefix: String,
        onChanged: () -> Unit,
        block: suspend () -> Unit
    ) {
        scope.launch {
            runCatching { block() }
                .onSuccess {
                    Toast.makeText(activity, successMsg, Toast.LENGTH_SHORT).show()
                    onChanged()
                }
                .onFailure { e ->
                    Log.w(TAG, "$failPrefix: ${e.message}", e)
                    Toast.makeText(
                        activity,
                        "$failPrefix: ${e.message ?: activity.getString(R.string.actions_unknown_error)}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }
}
