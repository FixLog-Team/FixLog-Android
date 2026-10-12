package kr.co.fixlog.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 앱 다크/라이트 테마 설정을 저장하고 적용한다.
 * - SYSTEM: 기기 설정을 따름(MODE_NIGHT_FOLLOW_SYSTEM)
 * - LIGHT / DARK: 강제 지정
 *
 * 저장은 SharedPreferences, 적용은 [AppCompatDelegate.setDefaultNightMode].
 * 앱 시작 시 [apply]를 호출하고, 설정 변경 시 [setMode]를 호출하면 즉시 반영된다.
 */
object ThemeManager {
    private const val PREF_NAME = "fixlog_theme"
    private const val KEY_MODE = "theme_mode"

    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 저장된 모드(SYSTEM/LIGHT/DARK). 기본값 SYSTEM. */
    fun getMode(context: Context): Int =
        prefs(context).getInt(KEY_MODE, SYSTEM)

    /** 모드를 저장하고 즉시 적용한다. */
    fun setMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_MODE, mode).apply()
        applyMode(mode)
    }

    /** 앱 시작 시(또는 Application.onCreate) 저장된 모드를 적용한다. */
    fun apply(context: Context) {
        applyMode(getMode(context))
    }

    private fun applyMode(mode: Int) {
        val nightMode = when (mode) {
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
}
