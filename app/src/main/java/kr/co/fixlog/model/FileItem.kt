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
    val children: MutableList<FileItem> = mutableListOf()
)

enum class FileType {
    FOLDER,
    FILE
}