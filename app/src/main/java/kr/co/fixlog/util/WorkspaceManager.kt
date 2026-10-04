package kr.co.fixlog.util

import android.content.Context

/**
 * 현재 선택된 워크스페이스를 로컬(SharedPreferences)에 보관한다.
 *
 * FRONTEND 명세 §1.2:
 *  - 헤더 `X-Workspace-Id: {workspaceId}` 로 현재 워크스페이스를 지정한다.
 *  - **생략 시 개인 워크스페이스**로 동작한다.
 *  - 전환은 이 헤더만 바꾸면 되고 토큰 재발급은 불필요하다.
 *
 * 따라서 개인 워크스페이스(또는 미선택)일 때는 id를 비워 헤더를 붙이지 않게 한다.
 * [AuthInterceptor]가 [getWorkspaceHeader]로 헤더 부착 여부를 판단한다.
 */
object WorkspaceManager {
    private const val PREF_NAME = "fixlog_workspace"
    private const val KEY_ID = "workspace_id"
    private const val KEY_NAME = "workspace_name"
    private const val KEY_ROLE = "workspace_role"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 협업 워크스페이스 선택. 개인 워크스페이스로 전환할 때는 [selectPersonal]을 사용한다.
     */
    fun select(context: Context, workspaceId: String, name: String?, role: String?) {
        prefs(context).edit()
            .putString(KEY_ID, workspaceId)
            .putString(KEY_NAME, name)
            .putString(KEY_ROLE, role)
            .apply()
    }

    /** 개인 워크스페이스로 전환(헤더 미부착). id를 비운다. */
    fun selectPersonal(context: Context, name: String? = null) {
        prefs(context).edit()
            .remove(KEY_ID)
            .putString(KEY_NAME, name)
            .remove(KEY_ROLE)
            .apply()
    }

    /** 선택된 협업 워크스페이스 id. 개인/미선택이면 null. */
    fun getSelectedId(context: Context): String? =
        prefs(context).getString(KEY_ID, null)?.takeIf { it.isNotBlank() }

    fun getSelectedName(context: Context): String? =
        prefs(context).getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }

    fun getSelectedRole(context: Context): String? =
        prefs(context).getString(KEY_ROLE, null)?.takeIf { it.isNotBlank() }

    /** AuthInterceptor에서 사용: 협업 WS면 id, 개인/미선택이면 null(헤더 생략). */
    fun getWorkspaceHeader(context: Context): String? = getSelectedId(context)

    /** 로그아웃 시 선택 상태 초기화. 소유자 이름 캐시도 함께 비운다. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        OwnerNames.invalidate()
    }
}
