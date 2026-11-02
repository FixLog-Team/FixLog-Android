package kr.co.fixlog.model

/**
 * Represents a folder or file item in the document hierarchy.
 * Used for both the main RecyclerView list and the drawer navigation section.
 */
data class FileItem(
    val id: String,
    val name: String,
    val type: FileType,
    val date: String,
    val parentId: String? = null, // null means root level
    val owner: String? = null,    // 소유자(작성자) 표시명 또는 식별자
    val createUser: String? = null, // 생성자 userId(접근 필터용 원본 값)
    val labels: List<String> = emptyList(), // 문서 라벨(태그). 폴더는 비어 있음
    val orphaned: Boolean = false, // 원래 상위 폴더에 접근 불가라 다른 위치(가까운 접근 가능 조상)에 떠 있는 항목
    val children: MutableList<FileItem> = mutableListOf()
)

enum class FileType {
    FOLDER,
    FILE
}