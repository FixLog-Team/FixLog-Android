package kr.co.fixlog.util

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kr.co.fixlog.R
import kr.co.fixlog.data.remote.dto.FolderTreeDto

/**
 * 이동 대상 폴더 선택 팝업(문서/폴더 이동 공용).
 * 첫 행은 루트(최상위, 아이콘 없음), 이후 폴더 트리를 깊이별 들여쓰기 + 문서 목록과 같은 폴더 아이콘으로 보여준다.
 */
object FolderPickerDialog {

    private class Entry(val folderId: String?, val name: String, val depth: Int)

    /**
     * @param excluded 후보에서 뺄 폴더 id(자신·하위 모두 제외). 폴더 이동 시 순환 방지용.
     * @param onPick 선택된 폴더 id(루트면 null).
     */
    fun show(
        activity: AppCompatActivity,
        title: String,
        tree: List<FolderTreeDto>,
        excluded: Set<String> = emptySet(),
        onPick: (String?) -> Unit
    ) {
        val entries = mutableListOf(Entry(null, activity.getString(R.string.actions_root_top), -1))
        fun flatten(nodes: List<FolderTreeDto>, depth: Int) {
            for (node in nodes) {
                if (node.folderId in excluded) continue
                entries.add(Entry(node.folderId, node.folderName, depth))
                flatten(node.children, depth + 1)
            }
        }
        flatten(tree, 0)

        val density = activity.resources.displayMetrics.density
        val basePad = (24 * density).toInt()
        val indent = (20 * density).toInt()
        val adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(position: Int) = entries[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_folder_pick, parent, false)
                val e = entries[position]
                v.findViewById<ImageView>(R.id.iv_icon).visibility = if (e.folderId == null) View.GONE else View.VISIBLE
                v.findViewById<TextView>(R.id.tv_name).text = e.name
                v.setPaddingRelative(basePad + indent * e.depth.coerceAtLeast(0), v.paddingTop, v.paddingEnd, v.paddingBottom)
                return v
            }
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setAdapter(adapter) { _, which -> onPick(entries[which].folderId) }
            .show()
    }
}
