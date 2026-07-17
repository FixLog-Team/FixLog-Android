package kr.co.fixlog.util

import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.co.fixlog.data.remote.DocumentApi
import kr.co.fixlog.data.remote.FolderApi
import kr.co.fixlog.data.remote.dto.FolderTreeDto

/**
 * 문서 카드(Home/Documents 화면)에서 공통으로 쓰는 문서 CRUD 액션 메뉴.
 *
 * 폴더 브라우저(MainActivity)는 폴더+문서를 함께 다루므로 자체 구현을 쓰고,
 * 이 헬퍼는 "문서 전용" 카드에서 이름변경/복제/이동/삭제를 처리한다.
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
            "이름 변경" to { showRenameDialog(activity, scope, documentId, title, onChanged) },
            "복제" to { runAction(activity, scope, "문서를 복제했습니다", "복제 실패", onChanged) { DocumentApi.duplicate(documentId) } },
            "이동" to { showMovePicker(activity, scope, documentId, onChanged) },
            "삭제" to { confirmDelete(activity, scope, documentId, title, onChanged) }
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
            .setTitle("이름 변경")
            .setView(input)
            .setPositiveButton("변경") { dialog, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                when {
                    name.isBlank() -> Toast.makeText(activity, "이름을 입력하세요", Toast.LENGTH_SHORT).show()
                    name == current -> Unit
                    else -> runAction(activity, scope, "이름을 변경했습니다", "이름 변경 실패", onChanged) {
                        DocumentApi.updateTitle(documentId, name)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("취소") { d, _ -> d.cancel() }
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
            .setTitle("삭제")
            .setMessage("'${title.ifBlank { "Untitled" }}' 문서를 삭제할까요?")
            .setPositiveButton("삭제") { dialog, _ ->
                runAction(activity, scope, "삭제했습니다", "삭제 실패", onChanged) { DocumentApi.delete(documentId) }
                dialog.dismiss()
            }
            .setNegativeButton("취소") { d, _ -> d.cancel() }
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
                Toast.makeText(activity, "폴더 목록을 불러오지 못했습니다", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val candidates = mutableListOf<Pair<String?, String>>() // (folderId, 표시명)
            candidates.add(null to "루트(최상위)")
            flattenTree(tree, 0, candidates)

            AlertDialog.Builder(activity)
                .setTitle("이동할 위치 선택")
                .setItems(candidates.map { it.second }.toTypedArray()) { _, which ->
                    val destId = candidates[which].first
                    runAction(activity, scope, "이동했습니다", "이동 실패", onChanged) {
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
                        "$failPrefix: ${e.message ?: "알 수 없는 오류"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }
}
