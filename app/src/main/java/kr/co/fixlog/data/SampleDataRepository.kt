package kr.co.fixlog.data

import android.graphics.Color
import kr.co.fixlog.model.DrawerMenuIconType
import kr.co.fixlog.model.DrawerMenuItem
import kr.co.fixlog.model.DrawerSection
import kr.co.fixlog.model.FileItem
import kr.co.fixlog.model.FileType

/**
 * Provides sample/mock data for the document hierarchy and drawer menu.
 * Structured so a real data source can replace it later.
 */
object SampleDataRepository {

    /** Build the complete folder/file tree and return root-level items */
    fun getRootFileItems(): List<FileItem> {
        return buildSampleTree()
    }

    /** Get children of a specific folder by its id */
    fun getChildrenOf(parentId: String): List<FileItem> {
        val allItems = buildFlatMap()
        return allItems[parentId]?.children ?: emptyList()
    }

    /** Find a specific item by id */
    fun findItemById(itemId: String): FileItem? {
        return findInTree(buildSampleTree(), itemId)
    }

    /** Build drawer sections with dynamic data */
    fun getDrawerSections(): List<DrawerSection> {
        val navigationSection = DrawerSection(
            title = "NAVIGATION",
            items = mutableListOf(
                DrawerMenuItem(
                    id = "nav_my_documents",
                    title = "My Documents",
                    iconType = DrawerMenuIconType.FOLDER
                )
            )
        )

        val settingsSection = DrawerSection(
            title = "SETTINGS",
            items = mutableListOf(
                DrawerMenuItem(
                    id = "nav_logout",
                    title = "Logout",
                    iconType = DrawerMenuIconType.NONE,
                    textColor = Color.RED
                )
            )
        )

        return listOf(navigationSection, settingsSection)
    }

    // -- Private helpers --

    private fun buildSampleTree(): List<FileItem> {
        // Root-level items
        val glosign = FileItem(
            id = "folder_glosign",
            name = "Glosign",
            type = FileType.FOLDER,
            date = "2026. 4. 6."
        )
        val soongsil = FileItem(
            id = "folder_soongsil",
            name = "soongsil",
            type = FileType.FOLDER,
            date = "2026. 4. 6."
        )
        val gettingStarted = FileItem(
            id = "file_react",
            name = "Getting Started with React",
            type = FileType.FILE,
            date = "2024. 1. 15.",
            parentId = null
        )

        // Glosign children
        val dev = FileItem(
            id = "folder_dev",
            name = "dev",
            type = FileType.FOLDER,
            date = "2026. 4. 6.",
            parentId = "folder_glosign"
        )
        val product = FileItem(
            id = "folder_product",
            name = "product",
            type = FileType.FOLDER,
            date = "2026. 4. 6.",
            parentId = "folder_glosign"
        )
        glosign.children.addAll(listOf(dev, product))

        // dev children
        val apiSpec = FileItem(
            id = "file_api_spec",
            name = "API Specification",
            type = FileType.FILE,
            date = "2026. 3. 20.",
            parentId = "folder_dev"
        )
        val devNotes = FileItem(
            id = "file_dev_notes",
            name = "Development Notes",
            type = FileType.FILE,
            date = "2026. 3. 15.",
            parentId = "folder_dev"
        )
        dev.children.addAll(listOf(apiSpec, devNotes))

        // product children
        val roadmap = FileItem(
            id = "file_roadmap",
            name = "Product Roadmap",
            type = FileType.FILE,
            date = "2026. 2. 10.",
            parentId = "folder_product"
        )
        product.children.add(roadmap)

        // soongsil children
        val homework = FileItem(
            id = "folder_homework",
            name = "homework",
            type = FileType.FOLDER,
            date = "2026. 4. 1.",
            parentId = "folder_soongsil"
        )
        val lectureNotes = FileItem(
            id = "file_lecture",
            name = "Lecture Notes",
            type = FileType.FILE,
            date = "2026. 3. 28.",
            parentId = "folder_soongsil"
        )
        soongsil.children.addAll(listOf(homework, lectureNotes))

        // homework children
        val hw1 = FileItem(
            id = "file_hw1",
            name = "Assignment 1",
            type = FileType.FILE,
            date = "2026. 3. 25.",
            parentId = "folder_homework"
        )
        homework.children.add(hw1)

        return listOf(glosign, soongsil, gettingStarted)
    }

    /** Build a flat map of id -> FileItem for quick lookup */
    private fun buildFlatMap(): Map<String, FileItem> {
        val map = mutableMapOf<String, FileItem>()
        fun traverse(items: List<FileItem>) {
            for (item in items) {
                map[item.id] = item
                if (item.children.isNotEmpty()) {
                    traverse(item.children)
                }
            }
        }
        traverse(buildSampleTree())
        return map
    }

    /** Recursively find an item by id in the tree */
    private fun findInTree(items: List<FileItem>, id: String): FileItem? {
        for (item in items) {
            if (item.id == id) return item
            val found = findInTree(item.children, id)
            if (found != null) return found
        }
        return null
    }
}