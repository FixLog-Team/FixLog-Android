package kr.co.fixlog.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import com.google.android.material.bottomsheet.BottomSheetDialog
import kr.co.fixlog.R
import kr.co.fixlog.activity.SettingsActivity

/**
 * 하단 네비 "전체" 탭이 여는 그리드 다이얼로그.
 * 참조 디자인의 격자 아이콘 → 섹션 바로가기 모달. (설정만 라우팅 연결, 나머지는 후속)
 */
object AllDialog {
    fun show(context: Context) {
        val view = LayoutInflater.from(context).inflate(R.layout.view_all_sheet, null)
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(view)

        // 시트 컨테이너의 기본(흰색 사각형) 배경을 투명하게 → 콘텐츠의 둥근 상단 모서리가 그대로 보이게.
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<android.view.View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            sheet?.background = ColorDrawable(Color.TRANSPARENT)
        }

        // 설정 셀 → 설정 화면으로 이동 후 시트 닫기.
        view.findViewById<android.view.View>(R.id.cell_settings)?.setOnClickListener {
            context.startActivity(Intent(context, SettingsActivity::class.java))
            dialog.dismiss()
        }

        dialog.show()
    }
}
