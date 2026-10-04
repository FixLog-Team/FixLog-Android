package kr.co.fixlog.util

import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.data.remote.ShareApi
import kr.co.fixlog.data.remote.dto.PermissionDto

/**
 * 공유 다이얼로그(문서/폴더 공통). 이메일로 공유(ALLOW+다운로드 허용) 부여 + 현재 공유 대상 목록/회수.
 * kind: "document" | "folder".
 */
object ShareDialogHelper {

    fun show(activity: AppCompatActivity, scope: CoroutineScope, kind: String, id: String, name: String) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_share, null)
        val etEmail = view.findViewById<EditText>(R.id.et_email)
        val cbDownload = view.findViewById<CheckBox>(R.id.cb_download)
        val btnShare = view.findViewById<MaterialButton>(R.id.btn_share)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_empty)
        val llPermissions = view.findViewById<LinearLayout>(R.id.ll_permissions)

        val typeLabel = if (kind == "folder") activity.getString(R.string.tree_folder) else activity.getString(R.string.tree_document)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.share_dialog_title, name.ifBlank { typeLabel }))
            .setView(view)
            .setPositiveButton(activity.getString(R.string.common_close), null)
            .create()

        fun reload() {
            scope.launch {
                val perms = runCatching { ShareApi.listPermissions(kind, id) }.getOrDefault(emptyList())
                renderPermissions(activity, scope, kind, id, llPermissions, tvEmpty, perms) { reload() }
            }
        }

        btnShare.setOnClickListener {
            val email = etEmail.text?.toString()?.trim().orEmpty()
            if (email.isBlank()) {
                Toast.makeText(activity, activity.getString(R.string.share_enter_email), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnShare.isEnabled = false
            scope.launch {
                runCatching { ShareApi.share(kind, id, email, cbDownload.isChecked) }
                    .onSuccess {
                        Toast.makeText(activity, activity.getString(R.string.share_shared), Toast.LENGTH_SHORT).show()
                        etEmail.setText("")
                        reload()
                    }
                    .onFailure { e ->
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.share_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                btnShare.isEnabled = true
            }
        }

        dialog.show()
        reload()
    }

    private fun renderPermissions(
        activity: AppCompatActivity,
        scope: CoroutineScope,
        kind: String,
        id: String,
        container: LinearLayout,
        tvEmpty: TextView,
        perms: List<PermissionDto>,
        onChanged: () -> Unit
    ) {
        container.removeAllViews()
        tvEmpty.visibility = if (perms.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(activity)
        perms.forEach { p ->
            val row = inflater.inflate(R.layout.item_permission, container, false)
            row.findViewById<TextView>(R.id.tv_principal).text =
                (p.principalName?.ifBlank { null } ?: activity.getString(R.string.share_user)) +
                    (if (p.principalType.equals("GROUP", true)) activity.getString(R.string.share_group_suffix) else "")
            row.findViewById<TextView>(R.id.tv_badge).text =
                activity.getString(R.string.share_allowed) + (if (p.canDownload == false) activity.getString(R.string.share_no_export_suffix) else "")
            row.findViewById<ImageView>(R.id.btn_revoke).setOnClickListener {
                scope.launch {
                    runCatching { ShareApi.revoke(kind, id, p.permissionId) }
                        .onSuccess {
                            Toast.makeText(activity, activity.getString(R.string.share_revoked), Toast.LENGTH_SHORT).show()
                            onChanged()
                        }
                        .onFailure {
                            Toast.makeText(activity, activity.getString(R.string.share_revoke_failed), Toast.LENGTH_SHORT).show()
                        }
                }
            }
            container.addView(row)
        }
    }
}
