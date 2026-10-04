package kr.co.fixlog.util

import android.content.Context

/**
 * 마지막으로 접속한 AI 대화방을 로컬(SharedPreferences)에 저장한다.
 * 앱 시작/탭 전환 후 검색 화면으로 돌아왔을 때 이 대화방을 복원한다.
 *
 * NOTE: 워크스페이스별로 대화방이 분리될 수 있어, 저장 시 현재 워크스페이스 id도 함께 보관해
 *       다른 워크스페이스로 전환된 경우엔 복원하지 않는다.
 */
object ConversationManager {
    private const val PREF_NAME = "fixlog_conversation"
    private const val KEY_LAST_ID = "last_conversation_id"
    private const val KEY_WS = "last_conversation_ws"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun setLast(context: Context, conversationId: String) {
        prefs(context).edit()
            .putString(KEY_LAST_ID, conversationId)
            .putString(KEY_WS, WorkspaceManager.getSelectedId(context) ?: "")
            .apply()
    }

    /** 현재 워크스페이스 기준의 마지막 대화방 id. 없거나 워크스페이스가 바뀌었으면 null. */
    fun getLast(context: Context): String? {
        val p = prefs(context)
        val id = p.getString(KEY_LAST_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val savedWs = p.getString(KEY_WS, "") ?: ""
        val currentWs = WorkspaceManager.getSelectedId(context) ?: ""
        return if (savedWs == currentWs) id else null
    }

    fun clearLast(context: Context) {
        prefs(context).edit().remove(KEY_LAST_ID).remove(KEY_WS).apply()
    }
}
