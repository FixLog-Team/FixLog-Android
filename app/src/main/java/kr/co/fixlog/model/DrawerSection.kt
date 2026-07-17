package kr.co.fixlog.model

/**
 * Represents a section in the drawer menu (e.g., NAVIGATION, SETTINGS).
 * Each section has a title and a list of menu items.
 */
data class DrawerSection(
    val title: String,
    val items: MutableList<DrawerMenuItem>
)

/**
 * Represents an individual menu item within a drawer section.
 * Can be a navigation item (which may have folder/file data) or a settings item.
 */
data class DrawerMenuItem(
    val id: String,
    val title: String,
    val iconType: DrawerMenuIconType = DrawerMenuIconType.FOLDER,
    val textColor: Int? = null, // custom text color (e.g., red for Logout)
    val fileItem: FileItem? = null // linked file item for navigation items
)

enum class DrawerMenuIconType {
    FOLDER,
    FILE,
    NONE
}