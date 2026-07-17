package kr.co.fixlog.util

import android.content.Intent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kr.co.fixlog.R
import kr.co.fixlog.activity.DocumentsActivity
import kr.co.fixlog.activity.EditorActivity
import kr.co.fixlog.activity.HomeActivity
import kr.co.fixlog.activity.SearchActivity
import kr.co.fixlog.data.remote.DocumentApi
import kr.co.fixlog.databinding.ViewBottomNavBinding

/**
 * 공용 하단 네비게이션 배선. 참조 디자인의 5개 탭(홈/검색/문서/작성/전체)을
 * 액티비티 간 전환으로 구현한다. "전체"는 다이얼로그이므로 onAll 콜백으로 위임.
 *
 * - current 탭은 파란색으로 강조하고 클릭 시 no-op.
 * - 탭 전환은 REORDER_TO_FRONT + SINGLE_TOP으로 중복 인스턴스를 피한다(간이 탭 동작).
 */
object BottomNav {

    enum class Tab { HOME, SEARCH, DOCS, WRITE, ALL }

    fun setup(
        activity: AppCompatActivity,
        nav: ViewBottomNavBinding,
        current: Tab,
        onAll: () -> Unit
    ) {
        nav.navHome.setOnClickListener { go(activity, current, Tab.HOME) }
        nav.navSearch.setOnClickListener { go(activity, current, Tab.SEARCH) }
        nav.navDocs.setOnClickListener { go(activity, current, Tab.DOCS) }
        nav.navWrite.setOnClickListener { go(activity, current, Tab.WRITE) }
        nav.navAll.setOnClickListener { onAll() }

        val active: LinearLayout = when (current) {
            Tab.HOME -> nav.navHome
            Tab.SEARCH -> nav.navSearch
            Tab.DOCS -> nav.navDocs
            Tab.WRITE -> nav.navWrite
            Tab.ALL -> nav.navAll
        }
        val blue = ContextCompat.getColor(activity, R.color.editor_blue)
        (active.getChildAt(0) as? ImageView)?.setColorFilter(blue)
        (active.getChildAt(1) as? TextView)?.setTextColor(blue)
    }

    @Suppress("DEPRECATION")
    private fun go(activity: AppCompatActivity, current: Tab, target: Tab) {
        if (current == target) return
        val cls = when (target) {
            Tab.HOME -> HomeActivity::class.java
            Tab.SEARCH -> SearchActivity::class.java
            Tab.DOCS -> DocumentsActivity::class.java
            // 작성 탭은 "최근 작성 문서"를 열어야 하므로 별도 처리.
            Tab.WRITE -> { openWrite(activity); return }
            Tab.ALL -> return
        }
        val intent = Intent(activity, cls).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        activity.startActivity(intent)
        activity.overridePendingTransition(0, 0)
    }

    /**
     * 작성 탭 진입: 전에 작성한 문서가 있으면 가장 최근 문서를 열고, 없으면 새 문서 편집기를 연다.
     * 최근 문서 조회 실패(네트워크/서버)도 새 문서로 폴백한다.
     * 최신 문서를 확실히 반영하기 위해 REORDER_TO_FRONT는 쓰지 않고 새 인스턴스로 연다.
     */
    @Suppress("DEPRECATION")
    private fun openWrite(activity: AppCompatActivity) {
        activity.lifecycleScope.launch {
            val latest = runCatching { DocumentApi.list(folderId = null, page = 0, size = 1) }
                .getOrNull()?.items?.firstOrNull()
            val intent = Intent(activity, EditorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (latest != null) {
                    putExtra(EditorActivity.EXTRA_FILE_ID, latest.documentId)
                    putExtra(EditorActivity.EXTRA_FILE_NAME, latest.title)
                }
            }
            activity.startActivity(intent)
            activity.overridePendingTransition(0, 0)
        }
    }
}