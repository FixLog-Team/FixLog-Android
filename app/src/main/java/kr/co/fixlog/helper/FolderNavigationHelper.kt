package kr.co.fixlog.helper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kr.co.fixlog.data.remote.FolderApi
import kr.co.fixlog.data.remote.dto.FolderContentsDto
import kr.co.fixlog.model.FileItem
import kr.co.fixlog.model.FileType

/**
 * 폴더 트리 네비게이션 상태 관리 + 서버 폴더 API 조회.
 *
 * - 이전엔 SampleDataRepository의 in-memory 트리를 사용했으나, 서버 `/api/folders` 계열 API로 교체
 * - 네트워크 호출은 [scope] 위에서 launch되어 UI 스레드를 막지 않음
 * - 성공 시 onNavigationChanged, 실패 시 onError 콜백으로 알림
 * - 서버는 인증된 사용자 기준으로 소유 폴더를 판단한다(workspace 개념 없음).
 */
class FolderNavigationHelper(
    private val scope: CoroutineScope
) {

    // 현재 열린 경로 (루트로부터의 폴더 스택)
    private val pathStack = mutableListOf<PathEntry>()

    /** 성공 콜백: 표시할 아이템, 경로 표시 문자열, 루트 여부 */
    var onNavigationChanged: ((items: List<FileItem>, pathDisplay: String, isRoot: Boolean) -> Unit)? = null

    /** 실패 콜백: 네트워크/서버 오류 */
    var onError: ((Throwable) -> Unit)? = null

    /** 조회 시작(true)/종료(false) 콜백: 로딩 인디케이터 표시/해제에 사용. */
    var onLoadingChanged: ((Boolean) -> Unit)? = null

    fun navigateToRoot() {
        pathStack.clear()
        scope.launch {
            onLoadingChanged?.invoke(true)
            runCatching { FolderApi.getRootContents() }
                .onSuccess { contents ->
                    onNavigationChanged?.invoke(mapToFileItems(contents, parentId = null), "", true)
                }
                .onFailure { onError?.invoke(it) }
            onLoadingChanged?.invoke(false)
        }
    }

    fun navigateInto(folder: FileItem) {
        pathStack.add(PathEntry(folder.id, folder.name))
        loadCurrentFolder()
    }

    /** 한 단계 뒤로. 더 뒤로 갈 수 없으면 false 반환. */
    fun navigateBack(): Boolean {
        if (pathStack.isEmpty()) return false
        pathStack.removeAt(pathStack.size - 1)
        if (pathStack.isEmpty()) {
            navigateToRoot()
        } else {
            loadCurrentFolder()
        }
        return true
    }

    fun isAtRoot(): Boolean = pathStack.isEmpty()

    /** 현재 열린(루트가 아닌) 폴더의 id. 루트면 null. 외부에서 "새 폴더 생성 시 parentId"로 사용 가능. */
    fun currentFolderId(): String? = pathStack.lastOrNull()?.id

    /** 현재 경로의 폴더 이름 목록(루트 제외). breadcrumb 렌더링에 사용. */
    fun pathNames(): List<String> = pathStack.map { it.name }

    /**
     * 경로의 특정 깊이로 이동한다(breadcrumb 클릭용).
     * @param depth 0이면 루트, k이면 앞에서부터 k개 폴더까지 유지하고 그 위치를 연다.
     */
    fun navigateToDepth(depth: Int) {
        if (depth <= 0) {
            navigateToRoot()
            return
        }
        while (pathStack.size > depth) pathStack.removeAt(pathStack.size - 1)
        if (pathStack.isEmpty()) navigateToRoot() else loadCurrentFolder()
    }

    /** 외부에서 데이터 갱신을 트리거 (예: 새 폴더 생성 후). 현재 위치를 다시 로드한다. */
    fun reload() {
        if (pathStack.isEmpty()) navigateToRoot() else loadCurrentFolder()
    }

    private fun loadCurrentFolder() {
        val current = pathStack.last()
        scope.launch {
            onLoadingChanged?.invoke(true)
            runCatching { FolderApi.getFolderContents(current.id) }
                .onSuccess { contents ->
                    onNavigationChanged?.invoke(
                        mapToFileItems(contents, parentId = current.id),
                        buildPathDisplay(),
                        false
                    )
                }
                .onFailure { onError?.invoke(it) }
            onLoadingChanged?.invoke(false)
        }
    }

    /** FolderContentsDto → UI 모델(FileItem) 변환. 폴더 먼저, 문서 다음. */
    private fun mapToFileItems(contents: FolderContentsDto, parentId: String?): List<FileItem> {
        val folders = contents.folders.map {
            FileItem(
                id = it.folderId,
                name = it.folderName,
                type = FileType.FOLDER,
                date = it.updateTime?.take(10).orEmpty(),
                parentId = parentId
            )
        }
        val documents = contents.documents.map {
            FileItem(
                id = it.documentId,
                name = it.title,
                type = FileType.FILE,
                date = it.updateTime?.take(10).orEmpty(),
                parentId = parentId
            )
        }
        return folders + documents
    }

    private fun buildPathDisplay(): String = pathStack.joinToString(" / ") { it.name }

    private data class PathEntry(val id: String, val name: String)
}
