package com.filecleaner.app.ui.adapters

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.filecleaner.app.R
import com.filecleaner.app.data.FileItem
import com.filecleaner.app.utils.UndoHelper

class BrowseAdapter : ListAdapter<BrowseAdapter.Item, RecyclerView.ViewHolder>(DIFF) {

    sealed class Item {
        data class Header(val folderPath: String, val displayName: String, val fileCount: Int, val totalSize: Long = 0L) : Item()
        data class Folder(val path: String, val name: String, val itemCount: Int, val totalSize: Long) : Item()
        data class File(val fileItem: FileItem) : Item()
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_HEADER_GRID = 2
        private const val TYPE_FOLDER_GRID = 3
        private const val TYPE_FOLDER_LIST = 4
        private const val TYPE_FILE = 1
        private const val TYPE_FILE_GRID = 11
        private const val PAYLOAD_SELECTION = "selection"

        private val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(a: Item, b: Item): Boolean = when {
                a is Item.Header && b is Item.Header -> a.folderPath == b.folderPath
                a is Item.Folder && b is Item.Folder -> a.path == b.path
                a is Item.File && b is Item.File -> a.fileItem.path == b.fileItem.path
                else -> false
            }
            override fun areContentsTheSame(a: Item, b: Item): Boolean = a == b
        }
    }

    var viewMode: ViewMode = ViewMode.LIST_MD
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    /** Separate view mode for folder headers — when grid, shows folder cards instead of list headers. */
    var folderViewMode: ViewMode = ViewMode.GRID_MD
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    var onItemClick: ((FileItem) -> Unit)? = null
    var onItemLongClick: ((FileItem, View) -> Unit)? = null
    var onHeaderClick: ((String) -> Unit)? = null
    var onSelectionChanged: ((List<FileItem>) -> Unit)? = null

    // I3: Use shared color resolution from FileItemUtils
    private var colors: FileItemUtils.AdapterColors? = null

    // ── Selection state ─────────────────────────────────────────────────
    val selectedPaths = mutableSetOf<String>()
    var selectionMode = false
        private set

    fun enterSelectionMode(initialPath: String? = null) {
        if (!selectionMode) {
            selectionMode = true
            selectedPaths.clear()
        }
        if (initialPath != null) {
            selectedPaths.add(initialPath)
        }
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        notifySelectionChanged()
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedPaths.clear()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        onSelectionChanged?.invoke(emptyList())
    }

    fun toggleSelection(path: String) {
        if (path in selectedPaths) selectedPaths.remove(path) else selectedPaths.add(path)
        val position = currentList.indexOfFirst { it is Item.File && it.fileItem.path == path }
        if (position >= 0) {
            notifyItemChanged(position, PAYLOAD_SELECTION)
        }
        notifySelectionChanged()
    }

    /** Select item at a given adapter position (for drag-to-select). */
    fun selectAtPosition(position: Int) {
        val item = currentList.getOrNull(position) as? Item.File ?: return
        val path = item.fileItem.path
        if (path !in selectedPaths) {
            selectedPaths.add(path)
            notifyItemChanged(position)
            notifySelectionChanged()
        }
    }

    private fun notifySelectionChanged() {
        val selected = currentList.filterIsInstance<Item.File>().filter { it.fileItem.path in selectedPaths }.map { it.fileItem }
        onSelectionChanged?.invoke(selected)
        if (selected.isEmpty() && selectionMode) exitSelectionMode()
    }

    fun selectAll() {
        enterSelectionMode()
        currentList.filterIsInstance<Item.File>().forEach { selectedPaths.add(it.fileItem.path) }
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        notifySelectionChanged()
    }

    fun selectAllFiles() {
        enterSelectionMode()
        // D5: Cache File objects to avoid repeated filesystem stat calls per item
        currentList.filterIsInstance<Item.File>()
            .filter { !it.fileItem.file.isDirectory }
            .forEach { selectedPaths.add(it.fileItem.path) }
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        notifySelectionChanged()
    }

    fun selectAllFolders() {
        enterSelectionMode()
        currentList.filterIsInstance<Item.File>()
            .filter { it.fileItem.file.isDirectory }
            .forEach { selectedPaths.add(it.fileItem.path) }
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        notifySelectionChanged()
    }

    fun deselectAll() {
        exitSelectionMode()
    }

    fun getSelectedItems(): List<FileItem> =
        currentList.filterIsInstance<Item.File>().filter { it.fileItem.path in selectedPaths }.map { it.fileItem }

    fun getSelectedCount(): Int = selectedPaths.size

    /** Set of folder paths that are currently collapsed. */
    val collapsedFolders: MutableSet<String> = mutableSetOf()

    /** The full unfiltered list of items, before collapse filtering. */
    private var fullList: List<Item> = emptyList()

    fun getFileCount(): Int = currentList.count { it is Item.File }

    /**
     * Accepts the full list of items (headers + files) and submits only
     * the visible items (hiding files under collapsed folders) to the
     * RecyclerView differ.
     */
    /** When true, new folders are collapsed by default (user can expand individually). */
    var collapseByDefault = true

    fun submitFullList(items: List<Item>, commitCallback: Runnable? = null) {
        // Auto-collapse newly-appearing folders when collapseByDefault is on
        if (collapseByDefault) {
            for (item in items) {
                if (item is Item.Header && item.folderPath !in collapsedFolders) {
                    // Only auto-collapse if we haven't seen this folder before (user may have expanded it)
                    if (item.folderPath !in expandedByUser) {
                        collapsedFolders.add(item.folderPath)
                    }
                }
            }
        }
        fullList = items
        val visible = computeVisibleList()
        // Force diff by clearing first — ListAdapter ignores submitList if content is "same"
        submitList(null)
        if (commitCallback != null) {
            submitList(visible.toList(), commitCallback)
        } else {
            submitList(visible.toList())
        }
    }

    /** Tracks folders the user has explicitly expanded (survives re-submit). */
    private val expandedByUser = mutableSetOf<String>()

    /** Toggle a folder between collapsed and expanded. */
    fun toggleFolder(folderPath: String) {
        if (folderPath in collapsedFolders) {
            collapsedFolders.remove(folderPath)
            expandedByUser.add(folderPath)
        } else {
            collapsedFolders.add(folderPath)
            expandedByUser.remove(folderPath)
        }
        val visible = computeVisibleList()
        submitList(null)
        submitList(visible)
    }

    /** When true in direct browse mode, files are hidden (only folders shown). */
    var filesCollapsed = false

    /** Expand all folders. */
    fun expandAll() {
        collapsedFolders.clear()
        filesCollapsed = false
        expandedByUser.clear()
        val visible = computeVisibleList()
        // Force update by submitting null then the new list
        submitList(null)
        submitList(visible)
    }

    /** Collapse all folders. */
    fun collapseAll() {
        collapsedFolders.clear()
        for (item in fullList) {
            if (item is Item.Header) {
                collapsedFolders.add(item.folderPath)
            }
        }
        expandedByUser.clear()
        filesCollapsed = true
        val visible = computeVisibleList()
        // Force update by submitting null then the new list
        submitList(null)
        submitList(visible)
    }

    /** Returns true if any folder is currently expanded (or files are visible in direct browse). */
    fun hasExpandedFolders(): Boolean {
        val allHeaders = fullList.filterIsInstance<Item.Header>().map { it.folderPath }
        if (allHeaders.isNotEmpty()) {
            return allHeaders.any { it !in collapsedFolders }
        }
        // Direct browse mode: "expanded" means files are visible
        return !filesCollapsed
    }

    /** Filters the full list, keeping headers but removing files under collapsed folders. */
    private fun computeVisibleList(): List<Item> {
        val result = mutableListOf<Item>()
        var currentFolderCollapsed = false
        for (item in fullList) {
            when (item) {
                is Item.Header -> {
                    currentFolderCollapsed = item.folderPath in collapsedFolders
                    result.add(item)
                }
                is Item.Folder -> {
                    result.add(item)
                }
                is Item.File -> {
                    if (!currentFolderCollapsed && !filesCollapsed) {
                        result.add(item)
                    }
                }
            }
        }
        return result
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Item.Header -> if (folderViewMode.usesGridLayout) TYPE_HEADER_GRID else TYPE_HEADER
        is Item.Folder -> if (folderViewMode.usesGridLayout) TYPE_FOLDER_GRID else TYPE_FOLDER_LIST
        is Item.File -> when {
            viewMode.usesGridLayout -> TYPE_FILE_GRID
            else -> TYPE_FILE
        }
    }

    fun isHeader(position: Int): Boolean = position in currentList.indices &&
        (getItem(position) is Item.Header || getItem(position) is Item.Folder)

    fun isFolderItem(position: Int): Boolean = position in currentList.indices && getItem(position) is Item.Folder

    fun isSectionHeader(position: Int): Boolean = position in currentList.indices && getItem(position) is Item.Header

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_folder_header, parent, false))
            TYPE_HEADER_GRID -> FolderGridViewHolder(inflater.inflate(R.layout.item_folder_grid, parent, false))
            TYPE_FOLDER_GRID -> FolderGridViewHolder(inflater.inflate(R.layout.item_folder_grid, parent, false))
            TYPE_FOLDER_LIST -> HeaderViewHolder(inflater.inflate(R.layout.item_folder_header, parent, false))
            else -> {
                val layoutRes = when (viewType) {
                    TYPE_FILE_GRID -> R.layout.item_file_grid
                    else -> R.layout.item_file
                }
                FileViewHolder(inflater.inflate(layoutRes, parent, false))
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION) && holder is FileViewHolder) {
            val item = getItem(position) as? Item.File ?: return
            val fileItem = item.fileItem
            val isSelected = fileItem.path in selectedPaths
            val ctx = holder.itemView.context
            val c = colors ?: FileItemUtils.resolveColorsWithSelection(ctx).also { colors = it }

            // Partial rebind: only update selection visual state (skip icon, text, thumbnail)
            val card = holder.itemView as? com.google.android.material.card.MaterialCardView
            if (isSelected) {
                card?.setCardBackgroundColor(c.selectedBg)
                card?.strokeColor = c.selectedBorder
            } else {
                card?.setCardBackgroundColor(c.surface)
                card?.strokeColor = c.border
            }

            val cb = holder.check as? CheckBox
            if (selectionMode && cb != null) {
                cb.visibility = View.VISIBLE
                cb.isChecked = isSelected
                cb.isClickable = false
                cb.contentDescription = ctx.getString(
                    if (isSelected) R.string.a11y_deselect_file else R.string.a11y_select_file, fileItem.name)
            } else {
                cb?.visibility = View.GONE
            }

            holder.itemView.contentDescription = ctx.getString(
                if (isSelected) R.string.a11y_file_selected else R.string.a11y_file_info,
                fileItem.name, holder.meta?.text ?: fileItem.sizeReadable)
            // §G1: Set stateDescription so TalkBack announces selection state
            ViewCompat.setStateDescription(holder.itemView,
                ctx.getString(if (isSelected) R.string.a11y_file_selected else R.string.a11y_file_info,
                    fileItem.name, holder.meta?.text ?: fileItem.sizeReadable))
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    /** Callback for when a folder item (in direct browse mode) is clicked. */
    var onFolderClick: ((String) -> Unit)? = null

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is Item.Header -> {
                when (holder) {
                    is FolderGridViewHolder -> bindFolderGrid(holder, item)
                    is HeaderViewHolder -> bindHeader(holder, item)
                }
            }
            is Item.Folder -> {
                when (holder) {
                    is FolderGridViewHolder -> bindFolderGridItem(holder, item)
                    is HeaderViewHolder -> bindFolderListItem(holder, item)
                }
            }
            is Item.File -> bindFile(holder as FileViewHolder, item.fileItem)
        }
    }

    private fun bindHeader(holder: HeaderViewHolder, header: Item.Header) {
        holder.folderName.text = header.displayName
        holder.folderSize.text = UndoHelper.formatBytes(header.totalSize)
        holder.folderCount.text = holder.itemView.context.resources.getQuantityString(R.plurals.n_files, header.fileCount, header.fileCount)

        val isCollapsed = header.folderPath in collapsedFolders
        holder.chevron.setImageResource(
            if (isCollapsed) R.drawable.ic_arrow_down else R.drawable.ic_chevron_up
        )
        // Rotate chevron: 0 degrees when expanded (arrow pointing down), 180 when collapsed
        holder.chevron.rotation = 0f

        val ctx = holder.itemView.context
        // §G1: Rich folder header description with file count and size
        val folderDesc = ctx.getString(R.string.a11y_folder_header,
            header.displayName, header.fileCount, UndoHelper.formatBytes(header.totalSize))
        holder.itemView.contentDescription = if (isCollapsed) {
            "$folderDesc, ${ctx.getString(R.string.a11y_expand_folder, header.displayName)}"
        } else {
            "$folderDesc, ${ctx.getString(R.string.a11y_collapse_folder, header.displayName)}"
        }
        // §G1: Folder-specific state description for expand/collapse state
        ViewCompat.setStateDescription(holder.itemView,
            if (isCollapsed) ctx.getString(R.string.a11y_folder_collapsed, header.displayName)
            else ctx.getString(R.string.a11y_folder_expanded, header.displayName))

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val h = getItem(pos) as? Item.Header ?: return@setOnClickListener
                onHeaderClick?.invoke(h.folderPath)
            }
        }
    }

    private fun bindFolderGridItem(holder: FolderGridViewHolder, folder: Item.Folder) {
        holder.folderName.text = folder.name
        val ctx = holder.itemView.context
        val meta = buildString {
            if (folder.totalSize > 0) append(UndoHelper.formatBytes(folder.totalSize))
            if (folder.itemCount > 0) {
                if (isNotEmpty()) append(" (")
                append(ctx.resources.getQuantityString(R.plurals.n_files, folder.itemCount, folder.itemCount))
                if (folder.totalSize > 0) append(")")
            }
        }
        holder.folderMeta.text = meta
        holder.itemView.setOnClickListener { onFolderClick?.invoke(folder.path) }
    }

    private fun bindFolderListItem(holder: HeaderViewHolder, folder: Item.Folder) {
        holder.folderName.text = folder.name
        holder.folderSize.text = if (folder.totalSize > 0) UndoHelper.formatBytes(folder.totalSize) else ""
        holder.folderCount.text = holder.itemView.context.resources.getQuantityString(R.plurals.n_files, folder.itemCount, folder.itemCount)
        holder.chevron.setImageResource(R.drawable.ic_chevron_right)
        holder.chevron.rotation = 0f
        holder.itemView.setOnClickListener { onFolderClick?.invoke(folder.path) }
    }

    private fun bindFolderGrid(holder: FolderGridViewHolder, header: Item.Header) {
        holder.folderName.text = header.displayName
        val ctx = holder.itemView.context
        val meta = buildString {
            if (header.totalSize > 0) append(UndoHelper.formatBytes(header.totalSize))
            if (header.fileCount > 0) {
                if (isNotEmpty()) append(" (")
                append(ctx.resources.getQuantityString(R.plurals.n_files, header.fileCount, header.fileCount))
                if (header.totalSize > 0) append(")")
            }
        }
        holder.folderMeta.text = meta

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val h = getItem(pos) as? Item.Header ?: return@setOnClickListener
                onHeaderClick?.invoke(h.folderPath)
            }
        }
    }

    private fun bindFile(holder: FileViewHolder, item: FileItem) {
        holder.name.text = item.name
        val ctx = holder.itemView.context
        val c = colors ?: FileItemUtils.resolveColorsWithSelection(ctx).also { colors = it }
        val isSelected = item.path in selectedPaths

        // Scale grid thumbnail height to match cell width (≈ square aspect ratio)
        if (viewMode.usesGridLayout) {
            val lp = holder.icon.layoutParams
            val screenWidth = ctx.resources.displayMetrics.widthPixels
            val cellWidth = screenWidth / viewMode.spanCount
            lp.height = cellWidth
            holder.icon.layoutParams = lp
        }

        // Resize icon/container and card padding based on mode + size
        if (!viewMode.usesGridLayout) {
            val density = ctx.resources.displayMetrics.density
            val sizePx = (viewMode.iconSizeDp * density).toInt()
            val container = holder.icon.parent as? android.view.View
            if (container != null) {
                val clp = container.layoutParams
                clp.width = sizePx
                clp.height = sizePx
                container.layoutParams = clp
            }
            // Scale card inner padding for compact sizes
            val padPx = (viewMode.listCardPaddingDp * density).toInt()
            val innerLayout = (holder.itemView as? android.view.ViewGroup)?.getChildAt(0) as? android.view.ViewGroup
            val contentRow = innerLayout?.let {
                if (it.childCount > 1) it.getChildAt(1) as? android.view.ViewGroup else it.getChildAt(0) as? android.view.ViewGroup
            }
            contentRow?.setPadding(padPx, padPx, padPx, padPx)
            // Hide meta line for the most compact size
            holder.meta?.visibility = if (viewMode.listMetaVisible) View.VISIBLE else View.GONE
        }

        // Load thumbnail or icon
        FileItemUtils.loadThumbnail(holder.icon, item, viewMode.showsRichThumbnails)

        // Card colors: selection highlight or default
        val card = holder.itemView as? com.google.android.material.card.MaterialCardView
        if (isSelected) {
            card?.setCardBackgroundColor(c.selectedBg)
            card?.strokeColor = c.selectedBorder
        } else {
            card?.setCardBackgroundColor(c.surface)
            card?.strokeColor = c.border
        }

        // Meta line
        holder.meta?.let { FileItemUtils.buildMeta(it, item) }

        // Checkbox visibility based on selection mode
        val cb = holder.check as? CheckBox
        if (selectionMode && cb != null) {
            cb.visibility = View.VISIBLE
            cb.isChecked = isSelected
            cb.isClickable = false // click handled by itemView
            cb.contentDescription = ctx.getString(
                if (isSelected) R.string.a11y_deselect_file else R.string.a11y_select_file, item.name)
        } else {
            cb?.visibility = View.GONE
        }

        // Click handlers
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val current = getItem(pos)
                if (current is Item.File) {
                    if (selectionMode) {
                        toggleSelection(current.fileItem.path)
                    } else {
                        onItemClick?.invoke(current.fileItem)
                    }
                }
            }
        }
        holder.itemView.setOnLongClickListener { v ->
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val current = getItem(pos)
                if (current is Item.File) {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onItemLongClick?.invoke(current.fileItem, v)
                }
            }
            true
        }

        holder.itemView.contentDescription = ctx.getString(
            if (isSelected) R.string.a11y_file_selected else R.string.a11y_file_info,
            item.name, holder.meta?.text ?: item.sizeReadable)
        // §G1: Set stateDescription so TalkBack announces selection state
        ViewCompat.setStateDescription(holder.itemView,
            ctx.getString(if (isSelected) R.string.a11y_file_selected else R.string.a11y_file_info,
                item.name, holder.meta?.text ?: item.sizeReadable))
    }

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val folderName: TextView = view.findViewById(R.id.tv_folder_name)
        val folderSize: TextView = view.findViewById(R.id.tv_folder_size)
        val folderCount: TextView = view.findViewById(R.id.tv_folder_count)
        val chevron: ImageView = view.findViewById(R.id.iv_chevron)
    }

    class FolderGridViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val folderIcon: ImageView = view.findViewById(R.id.iv_folder_icon)
        val folderName: TextView = view.findViewById(R.id.tv_folder_name)
        val folderMeta: TextView = view.findViewById(R.id.tv_folder_meta)
    }

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_file_icon)
        val name: TextView = view.findViewById(R.id.tv_file_name)
        val meta: TextView? = view.findViewById(R.id.tv_file_meta)
        val check: View? = view.findViewById(R.id.cb_select)
    }
}
