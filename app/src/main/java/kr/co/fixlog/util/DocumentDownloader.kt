package kr.co.fixlog.util

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.fixlog.R
import kr.co.fixlog.data.remote.DocumentApi
import java.io.IOException

/**
 * 문서 PDF 다운로드(웹의 useDownloadDocument 대응).
 *  - GET /api/documents/{id}/download 로 서버가 만든 PDF를 받아
 *  - 기기의 "다운로드/FixLog" 폴더에 "{제목}.pdf"로 저장한다(MediaStore — 저장 권한 불필요).
 *  - 같은 이름이 있으면 시스템이 "(1)" 등을 붙여 구분한다.
 * 권한/워크스페이스 정책상 다운로드가 금지된 경우 서버가 403을 주며, 이때 전용 안내를 띄운다.
 */
object DocumentDownloader {

    private const val TAG = "DocumentDownloader"
    private const val SUB_DIR = "FixLog"

    fun download(activity: AppCompatActivity, scope: CoroutineScope, documentId: String, title: String) {
        LoadingIndicator.show(activity)
        scope.launch {
            val result = runCatching {
                val bytes = DocumentApi.downloadPdf(documentId)
                save(activity, fileNameOf(title.ifBlank { documentId }), bytes)
            }
            LoadingIndicator.hide(activity)
            result
                .onSuccess { uri -> showSaved(activity, uri) }
                .onFailure { e ->
                    Log.w(TAG, "다운로드 실패", e)
                    val msg = if ((e as? DocumentApi.HttpStatusException)?.status == 403)
                        activity.getString(R.string.download_forbidden)
                    else activity.getString(R.string.download_failed)
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }
        }
    }

    /** 파일명에 쓸 수 없는 문자를 바꾸고 길이를 제한한다. */
    private fun fileNameOf(title: String): String {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(100)
        return "${safe.ifBlank { "document" }}.pdf"
    }

    private suspend fun save(activity: AppCompatActivity, fileName: String, bytes: ByteArray): Uri =
        withContext(Dispatchers.IO) {
            val resolver = activity.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("openOutputStream failed")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null) // 쓰다 실패한 빈 파일은 남기지 않는다
                throw e
            }
            uri
        }

    /** 저장 완료 안내 + "열기"(PDF 뷰어로 바로 열기). 하단 네비가 있으면 그 위에 띄운다. */
    private fun showSaved(activity: AppCompatActivity, uri: Uri) {
        val root = activity.findViewById<View>(android.R.id.content)
        Snackbar.make(root, activity.getString(R.string.download_saved), Snackbar.LENGTH_LONG)
            .setActionTextColor(ContextCompat.getColor(activity, R.color.snackbar_action))
            .setAction(activity.getString(R.string.common_open_file)) {
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    activity.startActivity(view)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(activity, activity.getString(R.string.download_no_viewer), Toast.LENGTH_SHORT).show()
                }
            }
            .apply {
                activity.findViewById<View>(R.id.bottom_nav)?.takeIf { it.isShown }?.let { anchorView = it }
            }
            .show()
    }
}
