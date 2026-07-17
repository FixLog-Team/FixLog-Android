package kr.co.fixlog.util

import android.content.Intent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kr.co.fixlog.R
import kr.co.fixlog.activity.DocumentsActivity
import kr.co.fixlog.activity.EditorActivity
import kr.co.fixlog.activity.HomeActivity
import kr.co.fixlog.activity.SearchActivity
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
            Tab.WRITE -> EditorActivity::class.java
            Tab.ALL -> return
        }
        val intent = Intent(activity, cls).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        activity.startActivity(intent)
        activity.overridePendingTransition(0, 0)
    }
}