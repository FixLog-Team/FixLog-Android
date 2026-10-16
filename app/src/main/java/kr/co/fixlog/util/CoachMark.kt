package kr.co.fixlog.util

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kr.co.fixlog.R
import kr.co.fixlog.databinding.ViewCoachMarkBinding

/**
 * 화면별 최초 진입 시 한 번만 보여주는 코치마크 튜토리얼.
 *
 * - 화면 전체를 어둡게 덮고 대상 뷰 영역만 뚫어 강조한 뒤, 옆에 설명 말풍선을 띄운다.
 * - 아무 곳이나 탭하면 다음 단계, 마지막 단계(또는 건너뛰기) 후 [key]를 기록해 다시 띄우지 않는다.
 * - 보이지 않는(GONE/미배치) 대상 단계는 건너뛴다.
 * - 표시하면 true를 반환하고, 끝나면(완료·건너뛰기·표시할 단계 없음) [onFinish]를 부른다.
 */
object CoachMark {
    private const val PREF_NAME = "fixlog_coach_mark"

    /**
     * [find]는 표시 직전(첫 레이아웃 후)에 호출돼, 목록 아이템처럼 나중에 생기는 뷰도 대상으로 삼을 수 있다.
     * 여러 뷰를 돌려주면 그 전체를 감싸는 영역을 한 번에 강조한다.
     */
    class Step(val messageRes: Int, val find: () -> List<View?>) {
        constructor(target: View, messageRes: Int) : this(messageRes, { listOf(target) })
    }

    private class Resolved(val targets: List<View>, val messageRes: Int)

    /** 이미 본 튜토리얼인지. 화면을 임시로 바꿔야 하는 튜토리얼은 [show] 전에 이걸로 먼저 확인한다. */
    fun isSeen(context: Context, key: String): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getBoolean(key, false)

    fun show(activity: Activity, key: String, vararg steps: Step, onFinish: () -> Unit = {}): Boolean {
        val prefs = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (isSeen(activity, key)) return false
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        // 첫 레이아웃이 끝난 뒤 대상 위치를 잡는다.
        root.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            val visible = steps.mapNotNull { s ->
                s.find().filterNotNull().filter { it.isShown && it.width > 0 }
                    .takeIf { it.isNotEmpty() }?.let { Resolved(it, s.messageRes) }
            }
            if (visible.isEmpty()) { onFinish(); return@post }
            val overlay = Overlay(activity, visible) {
                prefs.edit().putBoolean(key, true).apply()
                onFinish()
            }
            root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        return true
    }

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class Overlay(
        context: Context,
        private val steps: List<Resolved>,
        private val onDone: () -> Unit
    ) : FrameLayout(context) {

        private val density = resources.displayMetrics.density
        private val dimPaint = Paint().apply { color = Color.argb(170, 0, 0, 0) }
        private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val hole = RectF()
        private val card = ViewCoachMarkBinding.inflate(LayoutInflater.from(context), this, false)
        private var index = 0

        init {
            setWillNotDraw(false)
            // CLEAR 블렌딩이 창 배경까지 뚫지 않도록 별도 레이어에 그린다.
            setLayerType(LAYER_TYPE_HARDWARE, null)
            isClickable = true
            isFocusable = true
            addView(card.root)
            setOnClickListener { next() }
            card.tvSkip.setOnClickListener { finish() }
            bind()
        }

        private fun next() {
            if (index < steps.lastIndex) { index++; bind() } else finish()
        }

        private fun finish() {
            onDone()
            (parent as? ViewGroup)?.removeView(this)
        }

        private fun bind() {
            val step = steps[index]
            card.tvMessage.setText(step.messageRes)
            card.tvNext.text = context.getString(
                if (index == steps.lastIndex) R.string.coach_done else R.string.coach_next
            ) + "  ${index + 1}/${steps.size}"
            card.root.visibility = INVISIBLE // 위치 확정 전 깜빡임 방지
            requestLayout()
            invalidate()
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            val r1 = Rect()
            steps[index].targets.forEach { v -> Rect().also { v.getGlobalVisibleRect(it); r1.union(it) } }
            val me = IntArray(2).also { getLocationInWindow(it) }
            val pad = 6 * density
            hole.set(
                r1.left - me[0] - pad, r1.top - me[1] - pad,
                r1.right - me[0] + pad, r1.bottom - me[1] + pad
            )
            // 말풍선: 아래 공간이 넉넉하면 아래, 아니면 위, 둘 다 안 되면(큰 대상) 화면 하단.
            val gap = 12 * density
            val h = card.root.height.toFloat()
            val y = when {
                hole.bottom + gap + h <= height -> hole.bottom + gap
                hole.top - gap - h >= 0 -> hole.top - gap - h
                else -> height - h - 32 * density
            }
            card.root.translationY = y - card.root.top
            card.root.visibility = VISIBLE
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRoundRect(hole, 12 * density, 12 * density, clearPaint)
        }
    }
}
