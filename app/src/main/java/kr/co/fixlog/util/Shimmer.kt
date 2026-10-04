package kr.co.fixlog.util

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import java.util.WeakHashMap

/**
 * skeleton 뷰에 은은한 반짝임(알파 펄스) 애니메이션을 적용/해제한다.
 * 외부 라이브러리 없이 View.alpha 를 1.0↔0.4 로 반복시킨다.
 */
object Shimmer {
    private val animators = WeakHashMap<View, ObjectAnimator>()

    /** [view]에 반짝임 시작(이미 실행 중이면 무시). */
    fun start(view: View) {
        if (animators[view] != null) return
        val anim = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.4f).apply {
            duration = 750
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        animators[view] = anim
    }

    /** 반짝임 정지 + 알파 원복. */
    fun stop(view: View) {
        animators.remove(view)?.cancel()
        view.alpha = 1f
    }
}
