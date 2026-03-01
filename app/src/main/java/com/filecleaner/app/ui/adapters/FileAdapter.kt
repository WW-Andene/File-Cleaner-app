package com.filecleaner.app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.filecleaner.app.R
import com.filecleaner.app.data.FileItem
import com.filecleaner.app.ui.adapters.FileItemUtils.dpToPx

class FileAdapter(
    private val selectable: Boolean = true,
    private val onSelectionChanged: (List<FileItem>) -> Unit = {}
) : ListAdapter<FileItem, FileAdapter.FileVH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FileItem>() {
            override fun areItemsTheSame(a: FileItem, b: FileItem) = a.path == b.path
            override fun areContentsTheSame(a: FileItem, b: FileItem) = a == b
        }
        private const val TYPE_LIST = 0
        private const val TYPE_GRID = 1
    }

    // Selection tracked separately from FileItem (F-001)
    private val selectedPaths = mutableSetOf<String>()

    private val DUPLICATE_GROUP_COLOR_RES = listOf(
        R.color.dupGroup0, R.color.dupGroup1, R.color.dupGroup2,
        R.color.dupGroup3, R.color.dupGroup4, R.color.dupGroup5
    )

    var viewMode: ViewMode = ViewMode.LIST
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    var onItemClick: ((FileItem) -> Unit)? = null
    var onItemLongClick: ((FileItem, View) -> Unit)? = null

    inner class FileVH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_file_icon)
        val name: TextView = view.findViewById(R.id.tv_file_name)
        val meta: TextView? = view.findViewById(R.id.tv_file_meta)
        val check: CheckBox? = view.findViewById(R.id.cb_select)
    }

    override fun getItemViewType(position: Int): Int = when (viewMode) {
        ViewMode.LIST, ViewMode.LIST_WITH_THUMBNAILS -> TYPE_LIST
        ViewMode.GRID_SMALL, ViewMode.GRID_MEDIUM, ViewMode.GRID_LARGE -> TYPE_GRID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileVH {
        val layoutRes = if (viewType == TYPE_GRID) R.layout.item_file_grid else R.layout.item_file
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return FileVH(view)
    }

    override fun onBindViewHolder(holder: FileVH, position: Int) {
        val item = getItem(position)
        val isSelected = item.path in selectedPaths

        holder.name.text = item.name

        // Larger thumbnails for LIST_WITH_THUMBNAILS mode
        if (viewMode == ViewMode.LIST_WITH_THUMBNAILS) {
            val lp = holder.icon.layoutParams
            lp.width = 72.dpToPx(holder.itemView)
            lp.height = 72.dpToPx(holder.itemView)
            holder.icon.layoutParams = lp
        }

        // Load thumbnail for images/videos, category icon for everything else
        val isGrid = viewMode != ViewMode.LIST && viewMode != ViewMode.LIST_WITH_THUMBNAILS
        FileItemUtils.loadThumbnail(holder.icon, item, isGrid)

        // Visual state: duplicate group colouring → selection highlight → default
        val card = holder.itemView as? MaterialCardView
        if (item.duplicateGroup >= 0) {
            val colorRes = DUPLICATE_GROUP_COLOR_RES[item.duplicateGroup % DUPLICATE_GROUP_COLOR_RES.size]
            val color = ContextCompat.getColor(holder.itemView.context, colorRes)
            card?.setCardBackgroundColor(color) ?: holder.itemView.setBackgroundColor(color)
            card?.strokeColor = ContextCompat.getColor(holder.itemView.context, R.color.borderDefault)
        } else if (isSelected) {
            // §DP3: Selected state — primary character carrier, not just a checkbox
            val selBg = ContextCompat.getColor(holder.itemView.context, R.color.selectedBackground)
            val selBorder = ContextCompat.getColor(holder.itemView.context, R.color.selectedBorder)
            card?.setCardBackgroundColor(selBg) ?: holder.itemView.setBackgroundColor(selBg)
            card?.strokeColor = selBorder
        } else {
            val defaultColor = ContextCompat.getColor(holder.itemView.context, R.color.surfaceColor)
            card?.setCardBackgroundColor(defaultColor) ?: holder.itemView.setBackgroundColor(0x00000000)
            card?.strokeColor = ContextCompat.getColor(holder.itemView.context, R.color.borderDefault)
        }

        // Meta line (only in list layouts that have it)
        holder.meta?.let { FileItemUtils.buildMeta(it, item) }

        // Checkbox + accessibility (F-033)
        val ctx = holder.itemView.context
        if (selectable && holder.check != null) {
            holder.check.visibility = View.VISIBLE
            holder.check.isChecked = isSelected
            holder.check.contentDescription = ctx.getString(
                if (isSelected) R.string.a11y_deselect_file else R.string.a11y_select_file, item.name)
            val toggle = {
                toggleSelection(item.path)
                val nowSelected = item.path in selectedPaths
                holder.check.isChecked = nowSelected
                holder.check.contentDescription = ctx.getString(
                    if (nowSelected) R.string.a11y_deselect_file else R.string.a11y_select_file, item.name)
                // Immediate card visual feedback for selection (§DP3)
                if (item.duplicateGroup < 0) {
                    if (nowSelected) {
                        card?.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.selectedBackground))
                        card?.strokeColor = ContextCompat.getColor(ctx, R.color.selectedBorder)
                    } else {
                        card?.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.surfaceColor))
                        card?.strokeColor = ContextCompat.getColor(ctx, R.color.borderDefault)
                    }
                }
                notifySelectionChanged()
            }
            holder.check.setOnClickListener { toggle() }
            holder.itemView.setOnClickListener { toggle() }
            holder.itemView.contentDescription = ctx.getString(
                if (isSelected) R.string.a11y_file_selected else R.string.a11y_file_not_selected,
                item.name, holder.meta?.text ?: "")
        } else {
            holder.check?.visibility = View.GONE
            // Wire click and long-click for non-selectable mode
            holder.itemView.setOnClickListener { onItemClick?.invoke(item) }
            holder.itemView.setOnLongClickListener { v ->
                onItemLongClick?.invoke(item, v)
                true
            }
            holder.itemView.contentDescription = ctx.getString(
                R.string.a11y_file_info, item.name, holder.meta?.text ?: item.sizeReadable)
        }
    }

    private fun toggleSelection(path: String) {
        if (path in selectedPaths) selectedPaths.remove(path) else selectedPaths.add(path)
    }

    private fun notifySelectionChanged() {
        onSelectionChanged(currentList.filter { it.path in selectedPaths })
    }

    fun selectAll() {
        selectedPaths.addAll(currentList.map { it.path })
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    /** Select all-but-one from each duplicate group, keeping the newest copy. */
    fun selectAllDuplicatesExceptBest() {
        selectedPaths.clear()
        val groups = currentList.filter { it.duplicateGroup >= 0 }.groupBy { it.duplicateGroup }
        for ((_, group) in groups) {
            // Keep the newest file (highest lastModified); select the rest for deletion
            val sorted = group.sortedByDescending { it.lastModified }
            sorted.drop(1).forEach { selectedPaths.add(it.path) }
        }
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    fun deselectAll() {
        selectedPaths.clear()
        notifyDataSetChanged()
        onSelectionChanged(emptyList())
    }

    fun getSelectedItems(): List<FileItem> = currentList.filter { it.path in selectedPaths }

    /** Returns current selection for persistence across config changes. */
    fun getSelectedPaths(): Set<String> = selectedPaths.toSet()

    /** Restores selection state (e.g. after config change). */
    fun restoreSelection(paths: Set<String>) {
        selectedPaths.clear()
        selectedPaths.addAll(paths)
        notifyDataSetChanged()
        notifySelectionChanged()
    }

}
