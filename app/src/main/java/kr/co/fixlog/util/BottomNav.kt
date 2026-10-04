package kr.co.fixlog.util

import android.content.Intent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kr.co.fixlog.R
import kr.co.fixlog.activity.DocumentsActivity
import kr.co.fixlog.activity.SearchActivity
import kr.co.fixlog.activity.SettingsActivity
import kr.co.fixlog.activity.TrashActivity
import kr.co.fixlog.databinding.ViewBottomNavBinding

/**
 * 공용 하단 네비게이션 배선. 앱 정보 구조의 4개 탭(문서/휴지통/검색/설정)을 액티비티 전환으로 구현한다.
 *
 * - current 탭은 파란색으로 강조하고 클릭 시 no-op.
 * - 탭 전환은 REORDER_TO_FRONT + SINGLE_TOP으로 중복 인스턴스를 피한다(간이 탭 동작).
 */
object BottomNav {

    enum class Tab { DOCS, TRASH, SEARCH, SETTINGS }

    fun setup(
        activity: AppCompatActivity,
        nav: ViewBottomNavBinding,
        current: Tab
    ) {
        nav.navDocs.setOnClickListener { go(activity, current, Tab.DOCS) }
        nav.navTrash.setOnClickListener { go(activity, current, Tab.TRASH) }
        nav.navSearch.setOnClickListener { go(activity, current, Tab.SEARCH) }
        nav.navSettings.setOnClickListener { go(activity, current, Tab.SETTINGS) }

        val active: LinearLayout = when (current) {
            Tab.DOCS -> nav.navDocs
            Tab.TRASH -> nav.navTrash
            Tab.SEARCH -> nav.navSearch
            Tab.SETTINGS -> nav.navSettings
        }
        val blue = ContextCompat.getColor(activity, R.color.editor_blue)
        (active.getChildAt(0) as? ImageView)?.setColorFilter(blue)
        (active.getChildAt(1) as? TextView)?.setTextColor(blue)
    }

    @Suppress("DEPRECATION")
    private fun go(activity: AppCompatActivity, current: Tab, target: Tab) {
        if (current == target) return
        val cls = when (target) {
            Tab.DOCS -> DocumentsActivity::class.java
            Tab.TRASH -> TrashActivity::class.java
            Tab.SEARCH -> SearchActivity::class.java
            Tab.SETTINGS -> SettingsActivity::class.java
        }
        val intent = Intent(activity, cls).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        activity.startActivity(intent)
        activity.overridePendingTransition(0, 0)
    }
}
