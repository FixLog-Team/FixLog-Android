package kr.co.fixlog.util

import android.content.Context
import kr.co.fixlog.R
import kr.co.fixlog.data.remote.AuthApi
import kr.co.fixlog.data.remote.WorkspaceApi
import org.json.JSONObject

/**
 * 리소스 소유자(userId)를 표시용 이름으로 바꾸는 리졸버.
 * 웹의 `useOwnerName`을 미러링한다:
 *  - 현재 워크스페이스 구성원 목록(GET /api/workspaces/{id}/members)으로 id→이름 매핑
 *  - 세션 사용자(GET /auth/token)를 보강해 개인 워크스페이스에서도 본인 리소스는 이름 표시
 *
 * 문서/폴더의 createUser 는 userId(UUID)이므로 이 맵으로 이름을 찾는다.
 * 워크스페이스 키별로 캐시하며, 워크스페이스가 바뀌면 자동으로 다시 로드한다.
 */
object OwnerNames {

    private const val PERSONAL_KEY = "__personal__"

    @Volatile
    private var cachedKey: String? = null

    @Volatile
    private var cache: Map<String, String> = emptyMap()

    /** 현재 워크스페이스 기준 id→이름 맵을 반환(캐시). 실패해도 세션 사용자만이라도 담는다. */
    suspend fun load(context: Context): Map<String, String> {
        val ws = WorkspaceManager.getSelectedId(context)
        val key = ws ?: PERSONAL_KEY
        cache.takeIf { key == cachedKey && it.isNotEmpty() }?.let { return it }

        val map = mutableMapOf<String, String>()
        if (ws != null) {
            runCatching { WorkspaceApi.members(ws) }.getOrNull()?.forEach { m ->
                val id = m.userId?.takeIf { it.isNotBlank() } ?: return@forEach
                map[id] = m.userName?.takeIf { it.isNotBlank() } ?: (m.email ?: id)
            }
        }
        // 세션 사용자 보강(본인 리소스는 항상 이름 표시).
        runCatching { AuthApi.session() }.getOrNull()?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                val id = o.optString("userId")
                if (id.isNotBlank()) {
                    map[id] = o.optString("userName").ifBlank { o.optString("email").ifBlank { context.getString(R.string.label_me) } }
                }
            }
        }

        cache = map
        cachedKey = key
        return map
    }

    /** 소유자 id → 표시 이름. 알 수 없으면 null(호출부에서 생략/폴백). */
    fun resolve(map: Map<String, String>, ownerId: String?): String? =
        ownerId?.takeIf { it.isNotBlank() }?.let { map[it] }

    fun invalidate() {
        cachedKey = null
        cache = emptyMap()
    }
}
