package kr.co.fixlog.util

import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.data.remote.DocumentApi
import kr.co.fixlog.data.remote.FolderApi
import kr.co.fixlog.data.remote.dto.FolderTreeDto

/**
 * 문서 카드에서 공통으로 쓰는 문서 CRUD 액션 메뉴.
 *
 * "문서 전용" 카드에서 이름변경/복제/이동/삭제를 처리한다.
 * (폴더+문서를 함께 다루는 트리 리스트는 TreeItemActionsHelper를 사용)
 *
 * 서버 API 매핑:
 *   이름변경 → PATCH /api/documents/{id}/title
 *   복제     → POST  /api/documents/{id}/duplicate
 *   이동     → PATCH /api/documents/{id}/move  (대상은 GET /api/folders/tree로 선택)
 *   삭제     → DELETE /api/documents/{id}
 */
object DocumentActionsHelper {

    private const val TAG = "DocumentActions"

    /**
     * 문서 액션 메뉴를 띄운다.
     * @param onChanged 성공적으로 변경(이름/이동/복제/삭제)된 뒤 호출 — 목록 새로고침 용도.
     */
    fun show(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        documentId: String,
        title: String,
        onChanged: () -> Unit
    ) {
        val actions = listOf<Pair<String, () -> Unit>>(
            activity.getString(R.string.common_rename) to { showRenameDialog(activity, scope, documentId, title, onChanged) },
            activity.getString(R.string.actions_duplicate) to { runAction(activity, scope, activity.getString(R.string.actions_document_duplicated), activity.getString(R.string.actions_duplicate_failed), onChanged) { DocumentApi.duplicate(documentId) } },
            activity.getString(R.string.common_move) to { showMovePicker(activity, scope, documentId, onChanged) },
            activity.getString(R.string.actions_share) to { ShareDialogHelper.show(activity, scope, "document", documentId, title) },
            activity.getString(R.string.common_delete) to { confirmDelete(activity, scope, documentId, title, onChanged) }
        )
        AlertDialog.Builder(activity)
            .setTitle(title.ifBlank { "Untitled" })
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun showRenameDialog(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        documentId: String,
        current: String,
        onChanged: () -> Unit
    ) {
        val input = EditText(activity).apply {
            setText(current)
            setSelection(current.length)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.common_rename))
            .setView(input)
            .setPositiveButton(activity.getString(R.string.actions_change)) { dialog, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                when {
                    name.isBlank() -> Toast.makeText(activity, activity.getString(R.string.actions_enter_name), Toast.LENGTH_SHORT).show()
                    name == current -> Unit
                    else -> runAction(activity, scope, activity.getString(R.string.actions_name_changed), activity.getString(R.string.actions_rename_failed), onChanged) {
                        DocumentApi.updateTitle(documentId, name)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(activity.getString(R.string.common_cancel)) { d, _ -> d.cancel() }
            .show()
    }

    private fun confirmDelete(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        documentId: String,
        title: String,
        onChanged: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.common_delete))
            .setMessage(activity.getString(R.string.actions_delete_document_confirm, title.ifBlank { "Untitled" }))
            .setPositiveButton(activity.getString(R.string.common_delete)) { dialog, _ ->
                runAction(activity, scope, activity.getString(R.string.actions_deleted), activity.getString(R.string.actions_delete_failed), onChanged) { DocumentApi.delete(documentId) }
                dialog.dismiss()
            }
            .setNegativeButton(activity.getString(R.string.common_cancel)) { d, _ -> d.cancel() }
            .show()
    }

    /** 폴더 트리를 받아 들여쓰기 목록으로 보여주고, 선택 위치로 문서를 이동한다. 첫 항목은 루트. */
    private fun showMovePicker(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        documentId: String,
        onChanged: () -> Unit
    ) {
        scope.launch {
            val tree = runCatching { FolderApi.getFolderTree() }.getOrElse { e ->
                Log.w(TAG, "폴더 트리 조회 실패", e)
                Toast.makeText(activity, activity.getString(R.string.actions_load_folders_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val candidates = mutableListOf<Pair<String?, String>>() // (folderId, 표시명)
            candidates.add(null to activity.getString(R.string.actions_root_top))
            flattenTree(tree, 0, candidates)

            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.actions_select_move_destination))
                .setItems(candidates.map { it.second }.toTypedArray()) { _, which ->
                    val destId = candidates[which].first
                    runAction(activity, scope, activity.getString(R.string.actions_moved), activity.getString(R.string.actions_move_failed), onChanged) {
                        DocumentApi.move(documentId, destId)
                    }
                }
                .show()
        }
    }

    private fun flattenTree(
        nodes: List<FolderTreeDto>,
        depth: Int,
        out: MutableList<Pair<String?, String>>
    ) {
        for (node in nodes) {
            out.add(node.folderId to ("  ".repeat(depth) + node.folderName))
            flattenTree(node.children, depth + 1, out)
        }
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
