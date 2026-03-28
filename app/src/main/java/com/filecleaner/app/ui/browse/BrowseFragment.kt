package com.filecleaner.app.ui.browse

import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.filecleaner.app.MainActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.filecleaner.app.R
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem
import com.filecleaner.app.databinding.FragmentBrowseBinding
import com.filecleaner.app.data.UserPreferences
import com.filecleaner.app.ui.adapters.BrowseAdapter
import com.filecleaner.app.ui.adapters.ViewMode
import com.filecleaner.app.ui.common.RoundedDialogBuilder
import com.filecleaner.app.ui.common.BaseFileListFragment
import com.filecleaner.app.ui.common.FileContextMenu
import com.filecleaner.app.ui.common.FileListDividerDecoration
import com.filecleaner.app.utils.FileOpener
import com.filecleaner.app.utils.UndoHelper
import com.filecleaner.app.ui.common.BatchRenameDialog
import com.filecleaner.app.ui.common.CompressDialog
import com.filecleaner.app.utils.MotionUtil
import com.filecleaner.app.utils.SavedSearchManager
import com.filecleaner.app.utils.SearchQueryParser
import com.filecleaner.app.viewmodel.MainViewModel
import com.filecleaner.app.viewmodel.ScanState
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class BrowseFragment : Fragment() {

    private var _binding: FragmentBrowseBinding? = null
    private val binding get() = _binding!!
    private val vm: MainViewModel by activityViewModels()
    private lateinit var adapter: BrowseAdapter

    private var activeDialog: android.app.Dialog? = null
    private var currentViewMode = ViewMode.LIST_MD
    private val selectedExtensions = mutableSetOf<String>()
    private var searchQuery = ""
    private var searchDebounceJob: Job? = null
    private var shouldScrollToTop = false
    private var dividerDecoration: FileListDividerDecoration? = null
    private var searchTextWatcher: TextWatcher? = null
    private var dragStartListener: RecyclerView.OnItemTouchListener? = null
    private var dragMoveListener: RecyclerView.OnItemTouchListener? = null
    private lateinit var selectionBackCallback: OnBackPressedCallback
    private lateinit var browseBackCallback: OnBackPressedCallback

    // Direct filesystem browsing — default mode, allows instant file access without scan
    private var currentBrowsePath: String? = null
    private var directBrowseMode = true

    // File manager needs broad storage access; MANAGE_EXTERNAL_STORAGE grants it
    @Suppress("DEPRECATION")
    private val storagePath: String
        get() = if (try { UserPreferences.showRootFiles } catch (_: Exception) { false }) {
            "/"
        } else {
            Environment.getExternalStorageDirectory().absolutePath
        }

    // Virtual categories — sentinels, handled in refresh()
    private val VIRTUAL_RECENT = "RECENT"
    private val VIRTUAL_FAVORITES = "FAVORITES"

    private val categories by lazy {
        listOf(
            getString(R.string.all_files) to null,
            getString(R.string.cat_recent) to VIRTUAL_RECENT,
            getString(R.string.cat_favorites) to VIRTUAL_FAVORITES,
            *FileCategory.entries.map { "${it.emoji} ${getString(it.displayNameRes)}" to it as Any }.toTypedArray()
        )
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentBrowseBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Pad RecyclerView so the floating selection bar never obscures the last items
        binding.selectionActionBar.doOnLayout { bar ->
            binding.recyclerView.updatePadding(bottom = bar.height)
        }

        // B5: Restore all UI state from config change
        savedInstanceState?.let { state ->
            state.getInt(KEY_VIEW_MODE, -1).let { ordinal ->
                if (ordinal in ViewMode.entries.indices) currentViewMode = ViewMode.entries[ordinal]
            }
            state.getString(KEY_SEARCH_QUERY)?.let { searchQuery = it }
            state.getStringArrayList(KEY_EXTENSIONS)?.let { selectedExtensions.addAll(it) }
            state.getStringArrayList(KEY_COLLAPSED_FOLDERS)?.let { restoredCollapsedFolders.addAll(it) }
            directBrowseMode = state.getBoolean(KEY_DIRECT_BROWSE, true)
            state.getString(KEY_BROWSE_PATH)?.let { currentBrowsePath = it }
            restoredFolderViewMode = state.getInt(KEY_FOLDER_VIEW_MODE, -1)
            restoredScrollPos = state.getInt(KEY_SCROLL_POS, -1)
        }

        // RecyclerView with BrowseAdapter (supports folder headers)
        adapter = BrowseAdapter()
        adapter.viewMode = currentViewMode
        adapter.onItemClick = { item ->
            // In direct browse mode, tapping a folder navigates into it
            if (directBrowseMode && item.file.isDirectory) {
                navigateToDirectory(item.path)
            } else {
                FileOpener.openInViewer(requireContext(), item.file)
            }
        }
        adapter.onItemLongClick = { item, anchor ->
            FileContextMenu.show(requireContext(), anchor, item, contextMenuCallback,
                hasClipboard = vm.clipboardEntry.value != null)
        }
        adapter.onHeaderClick = { folderPath ->
            if (directBrowseMode) {
                navigateToDirectory(folderPath)
            } else {
                adapter.toggleFolder(folderPath)
                updateExpandCollapseButton()
            }
        }
        adapter.onFolderClick = { folderPath ->
            navigateToDirectory(folderPath)
        }
        // Back press exits selection mode before navigating back
        selectionBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                adapter.deselectAll()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBackCallback)

        // Back press navigates up directory in direct browse mode
        browseBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                val parent = currentBrowsePath?.let { java.io.File(it).parent }
                if (parent != null && parent != currentBrowsePath) {
                    navigateToDirectory(parent)
                } else {
                    isEnabled = false
                    directBrowseMode = false
                    currentBrowsePath = null
                    refresh()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, browseBackCallback)

        // Selection mode callbacks
        adapter.onSelectionChanged = { selected ->
            selectionBackCallback.isEnabled = selected.isNotEmpty()
            updateSelectionBar(selected)
        }

        // Selection bar buttons
        binding.btnSelectAllFiles.setOnClickListener { adapter.selectAllFiles() }
        binding.btnSelectAllFolders.setOnClickListener { adapter.selectAllFolders() }
        binding.btnSelectAllBrowse.setOnClickListener { adapter.selectAll() }
        binding.btnDeselectAllBrowse.setOnClickListener { adapter.deselectAll() }
        binding.btnDeleteSelected.setOnClickListener {
            val items = adapter.getSelectedItems()
            if (items.isNotEmpty()) {
                val totalSize = UndoHelper.totalSize(items)
                activeDialog?.dismiss()
                activeDialog = RoundedDialogBuilder(requireContext())
                    .setTitle(resources.getQuantityString(R.plurals.delete_n_files_title, items.size, items.size))
                    .setMessage(getString(R.string.confirm_delete_message,
                        try { com.filecleaner.app.data.UserPreferences.undoTimeoutMs / 1000 } catch (_: Exception) { 8 }))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        vm.deleteFiles(items)
                        adapter.deselectAll()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        binding.btnCompressSelected.setOnClickListener {
            val items = adapter.getSelectedItems()
            if (items.isNotEmpty()) {
                CompressDialog.show(requireContext(), items) { archiveName, paths ->
                    vm.compressFiles(paths, archiveName)
                    adapter.deselectAll()
                }
            }
        }
        binding.btnRenameSelected.setOnClickListener {
            val items = adapter.getSelectedItems()
            if (items.size >= 2) {
                BatchRenameDialog.show(requireContext(), items) { renames ->
                    vm.batchRename(renames)
                    adapter.deselectAll()
                }
            }
        }

        // Restore collapsed folders from config change
        if (restoredCollapsedFolders.isNotEmpty()) {
            adapter.collapsedFolders.addAll(restoredCollapsedFolders)
            restoredCollapsedFolders.clear()
        }
        // Restore folder view mode
        if (restoredFolderViewMode in ViewMode.entries.indices) {
            adapter.folderViewMode = ViewMode.entries[restoredFolderViewMode]
            restoredFolderViewMode = -1
        }
        dividerDecoration = FileListDividerDecoration(requireContext()) { position ->
            adapter.isHeader(position)
        }
        applyLayoutManager()
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.adapter = adapter
        // Disable stagger animation when user prefers reduced motion (§G4)
        if (MotionUtil.isReducedMotion(requireContext())) {
            binding.recyclerView.layoutAnimation = null
        }

        // Category and Sort popup menus (replacing collapsible filter panel)
        binding.btnCategoryFilter?.setOnClickListener { showCategoryPopup() }
        binding.btnSortOrder?.setOnClickListener { showSortPopup() }

        // Expand All / Collapse All toggle button
        binding.btnToggleExpandCollapse.setOnClickListener { toggleAllFolders() }
        updateExpandCollapseButton()

        // View mode and size popup menus
        binding.btnViewMode.setOnClickListener { showViewModePopup() }
        binding.btnSizeMode.setOnClickListener { showSizeModePopup() }

        // Swipe drag-to-select touch listener
        setupDragToSelect()

        // Empty state "Scan Now" button
        binding.btnScanNow.setOnClickListener {
            (activity as? MainActivity)?.requestPermissionsAndScan()
        }

        // Long-press search icon to open saved searches
        (binding.etSearch.parent?.parent as? com.google.android.material.textfield.TextInputLayout)
            ?.setStartIconOnClickListener { showSavedSearchesDialog() }

        // Search with 300ms debounce
        searchTextWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchDebounceJob?.cancel()
                val query = s?.toString()?.trim() ?: ""
                searchDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(BaseFileListFragment.SEARCH_DEBOUNCE_MS)
                    if (_binding == null) return@launch
                    searchQuery = query
                    shouldScrollToTop = true
                    refresh()
                }
            }
        }
        binding.etSearch.addTextChangedListener(searchTextWatcher)

        // Category spinner
        val labels = categories.map { it.first }
        val spinnerAdapter = ArrayAdapter(requireContext(),
            R.layout.item_spinner, labels)
        spinnerAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        binding.spinnerCategory.adapter = spinnerAdapter

        // Sort spinner
        val sortOptions = listOf(
            getString(R.string.sort_name_asc), getString(R.string.sort_name_desc),
            getString(R.string.sort_size_asc), getString(R.string.sort_size_desc),
            getString(R.string.sort_date_asc), getString(R.string.sort_date_desc)
        )
        val sortAdapter = ArrayAdapter(requireContext(),
            R.layout.item_spinner, sortOptions)
        sortAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        binding.spinnerSort.adapter = sortAdapter

        binding.spinnerCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedExtensions.clear()
                shouldScrollToTop = true
                refresh()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        binding.spinnerSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // B5: Restore spinner positions and search text after setup
        savedInstanceState?.let { state ->
            binding.spinnerSort.setSelection(state.getInt(KEY_SORT_ORDER, 0))
            binding.spinnerCategory.setSelection(state.getInt(KEY_CATEGORY_POS, 0))
            if (searchQuery.isNotEmpty()) binding.etSearch.setText(searchQuery)
        }

        vm.filesByCategory.observe(viewLifecycleOwner) { refresh() }

        vm.operationResult.observe(viewLifecycleOwner) { result ->
            Snackbar.make(binding.root, result.message, Snackbar.LENGTH_SHORT).show()
        }

        vm.deleteResult.observe(viewLifecycleOwner) { result ->
            UndoHelper.showUndoSnackbar(binding.root, result, vm)
        }

        // Observe "Browse folder" navigation from other tabs (e.g. arborescence)
        vm.navigateToBrowse.observe(viewLifecycleOwner) { folderPath ->
            if (folderPath != null) {
                // Reset category filter to "All files" and set search to folder path
                binding.spinnerCategory.setSelection(0, false)
                selectedExtensions.clear()
                val folderName = File(folderPath).name
                searchQuery = folderName
                binding.etSearch.setText(folderName)
                refresh()
                val displayName = folderDisplayName(folderPath)
                Snackbar.make(binding.root, getString(R.string.browsing_folder, displayName),
                    Snackbar.LENGTH_SHORT).show()
                vm.clearBrowseNavigation()
            }
        }
    }

    private fun updateSelectionBar(selected: List<FileItem>) {
        val bar = binding.selectionActionBar
        if (selected.isNotEmpty()) {
            bar.visibility = View.VISIBLE
            binding.tvBrowseSelectionCount.text = getString(R.string.browse_selected, selected.size)
            binding.btnRenameSelected.visibility = if (selected.size >= 2) View.VISIBLE else View.GONE
        } else {
            bar.visibility = View.GONE
        }
    }

    private var selectedCategoryPos = 0
    private var selectedSortPos = 0
    private val restoredCollapsedFolders = mutableSetOf<String>()
    private var restoredFolderViewMode = -1
    private var restoredScrollPos = -1

    /** Toggle between showing all items and folders only. */
    private fun toggleAllFolders() {
        if (adapter.hasExpandedFolders()) {
            adapter.collapseAll()
        } else {
            adapter.expandAll()
        }
        updateExpandCollapseButton()
    }

    /** Updates the Expand All / Collapse All button text and icon to reflect current state. */
    private fun updateExpandCollapseButton() {
        val binding = _binding ?: return
        binding.btnToggleExpandCollapse.visibility = View.VISIBLE
        val expanded = adapter.hasExpandedFolders()
        if (expanded) {
            binding.btnToggleExpandCollapse.text = getString(R.string.collapse_all)
            binding.btnToggleExpandCollapse.setIconResource(R.drawable.ic_chevron_up)
        } else {
            binding.btnToggleExpandCollapse.text = getString(R.string.expand_all)
            binding.btnToggleExpandCollapse.setIconResource(R.drawable.ic_arrow_down)
        }
    }

    private fun showCategoryPopup() {
        val anchor = binding.btnCategoryFilter ?: return
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        categories.forEachIndexed { index, (label, _) ->
            val item = popup.menu.add(0, index, index, label)
            item.isCheckable = true
            item.isChecked = binding.spinnerCategory.selectedItemPosition == index
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { item ->
            binding.spinnerCategory.setSelection(item.itemId)
            val label = categories[item.itemId].first
            anchor.text = if (item.itemId == 0) getString(R.string.browse_category_label) else label
            true
        }
        popup.show()
    }

    private fun showSortPopup() {
        val anchor = binding.btnSortOrder ?: return
        val sortOptions = listOf(
            getString(R.string.sort_name_asc), getString(R.string.sort_name_desc),
            getString(R.string.sort_size_asc), getString(R.string.sort_size_desc),
            getString(R.string.sort_date_asc), getString(R.string.sort_date_desc)
        )
        val popup = android.widget.PopupMenu(requireContext(), anchor)
        sortOptions.forEachIndexed { index, label ->
            val item = popup.menu.add(0, index, index, label)
            item.isCheckable = true
            item.isChecked = binding.spinnerSort.selectedItemPosition == index
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { item ->
            binding.spinnerSort.setSelection(item.itemId)
            anchor.text = sortOptions[item.itemId]
            true
        }
        popup.show()
    }

    private fun applyLayoutManager() {
        dividerDecoration?.let { binding.recyclerView.removeItemDecoration(it) }

        val fileIsGrid = currentViewMode.style == ViewMode.Style.GRID
        val folderIsGrid = adapter.folderViewMode.usesGridLayout

        // Use grid layout when either files or folders need multiple columns
        val needsGrid = fileIsGrid || folderIsGrid
        val fileSpans = if (fileIsGrid) currentViewMode.spanCount else 1
        val folderSpans = if (folderIsGrid) adapter.folderViewMode.spanCount else 1
        // LCM-based approach: use the larger span count as the total
        val totalSpans = maxOf(fileSpans, folderSpans, 1)

        binding.recyclerView.layoutManager = if (needsGrid && totalSpans > 1) {
            GridLayoutManager(requireContext(), totalSpans).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        return when {
                            // Section headers (scan mode) always take full width
                            adapter.isSectionHeader(position) -> totalSpans
                            // Folder items: 1 span each in grid, full width in list
                            adapter.isFolderItem(position) ->
                                if (folderIsGrid) totalSpans / folderSpans else totalSpans
                            // File items: 1 span each in grid, full width in list
                            else ->
                                if (fileIsGrid) totalSpans / fileSpans else totalSpans
                        }
                    }
                }
            }
        } else {
            LinearLayoutManager(requireContext())
        }

        if (!currentViewMode.usesGridLayout) {
            dividerDecoration?.let { binding.recyclerView.addItemDecoration(it) }
        }
    }

    // ── View Mode & Size PopupMenus ─────────────────────────────────────

    private fun showViewModePopup() {
        val popup = android.widget.PopupMenu(requireContext(), binding.btnViewMode)

        // Group 1: Folder display mode
        popup.menu.add(1, 10, 0, getString(R.string.view_mode_folders_list))
        popup.menu.add(1, 11, 1, getString(R.string.view_mode_folders_grid))
        popup.menu.setGroupCheckable(1, true, true)
        popup.menu.findItem(if (adapter.folderViewMode.usesGridLayout) 11 else 10)?.isChecked = true

        // Group 2: File display mode
        popup.menu.add(2, 20, 2, getString(R.string.view_mode_files_list))
        popup.menu.add(2, 21, 3, getString(R.string.view_mode_files_grid))
        popup.menu.setGroupCheckable(2, true, true)
        popup.menu.findItem(if (currentViewMode.usesGridLayout) 21 else 20)?.isChecked = true

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                10 -> { adapter.folderViewMode = ViewMode.LIST_MD; applyLayoutManager() }
                11 -> { adapter.folderViewMode = ViewMode.GRID_MD; applyLayoutManager() }
                20 -> { currentViewMode = ViewMode.of(ViewMode.Style.LIST, currentViewMode.size); adapter.viewMode = currentViewMode; applyLayoutManager() }
                21 -> { currentViewMode = ViewMode.of(ViewMode.Style.GRID, currentViewMode.size); adapter.viewMode = currentViewMode; applyLayoutManager() }
            }
            true
        }
        popup.show()
    }

    private fun showSizeModePopup() {
        val popup = android.widget.PopupMenu(requireContext(), binding.btnSizeMode)
        val sizes = listOf(
            getString(R.string.size_xxs) to ViewMode.Size.XXS,
            getString(R.string.size_xs) to ViewMode.Size.XS,
            getString(R.string.size_sm) to ViewMode.Size.SM,
            getString(R.string.size_md) to ViewMode.Size.MD,
            getString(R.string.size_lg) to ViewMode.Size.LG,
            getString(R.string.size_xl) to ViewMode.Size.XL
        )
        sizes.forEachIndexed { index, (label, _) ->
            popup.menu.add(0, index, index, label)
        }
        popup.setOnMenuItemClickListener { item ->
            val (_, size) = sizes[item.itemId]
            currentViewMode = ViewMode.of(currentViewMode.style, size)
            adapter.viewMode = currentViewMode
            applyLayoutManager()
            true
        }
        popup.show()
    }

    // ── Drag-to-select (swipe finger multi-selection) ───────────────────

    private fun setupDragToSelect() {
        var isDragging = false
        var lastSelectedPosition = RecyclerView.NO_POSITION

        dragMoveListener = object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_MOVE && adapter.selectionMode && isDragging) {
                    return true
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        if (adapter.selectionMode && isDragging) {
                            val child = rv.findChildViewUnder(e.x, e.y)
                            if (child != null) {
                                val position = rv.getChildAdapterPosition(child)
                                if (position != RecyclerView.NO_POSITION && position != lastSelectedPosition) {
                                    adapter.selectAtPosition(position)
                                    lastSelectedPosition = position
                                }
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isDragging = false
                        lastSelectedPosition = RecyclerView.NO_POSITION
                    }
                }
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        }
        binding.recyclerView.addOnItemTouchListener(dragMoveListener!!)

        // Use a secondary touch listener to detect when the user starts dragging
        // while in selection mode (hold + move triggers multi-select)
        dragStartListener = object : RecyclerView.OnItemTouchListener {
            private var startX = 0f
            private var startY = 0f
            private val touchSlop = android.view.ViewConfiguration.get(requireContext()).scaledTouchSlop

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = e.x
                        startY = e.y
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (adapter.selectionMode && !isDragging) {
                            val dx = e.x - startX
                            val dy = e.y - startY
                            if (Math.abs(dy) > touchSlop || Math.abs(dx) > touchSlop) {
                                isDragging = true
                                // Select the item at the start position if not already selected
                                val child = rv.findChildViewUnder(startX, startY)
                                if (child != null) {
                                    val pos = rv.getChildAdapterPosition(child)
                                    if (pos != RecyclerView.NO_POSITION) {
                                        adapter.selectAtPosition(pos)
                                        lastSelectedPosition = pos
                                    }
                                }
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isDragging = false
                        lastSelectedPosition = RecyclerView.NO_POSITION
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        }
        binding.recyclerView.addOnItemTouchListener(dragStartListener!!)
    }

    /** Navigate to a directory for direct browsing. */
    fun navigateToDirectory(path: String) {
        currentBrowsePath = path
        directBrowseMode = true
        browseBackCallback.isEnabled = path != storagePath
        searchQuery = ""
        _binding?.etSearch?.setText("")
        refreshDirectBrowse()
    }

    private fun refreshDirectBrowse() {
        // Show action row in direct browse mode
        binding.browseActionsRow?.visibility = View.VISIBLE
        binding.btnNew?.setOnClickListener { showCreateNewDialog() }
        binding.btnGoToPath?.setOnClickListener { showGoToPathDialog() }

        // Paste indicator — show when clipboard has content
        val hasClipboard = vm.clipboardEntry.value != null
        binding.btnPasteIndicator?.visibility = if (hasClipboard) View.VISIBLE else View.GONE
        binding.btnPasteIndicator?.setOnClickListener {
            val clip = vm.clipboardEntry.value ?: return@setOnClickListener
            val targetDir = currentBrowsePath ?: storagePath
            if (clip.mode == com.filecleaner.app.viewmodel.ClipboardManager.ClipboardMode.CUT) {
                vm.moveFile(clip.item.path, targetDir)
            } else {
                vm.copyFile(clip.item.path, targetDir)
            }
            vm.clipboard.clearClipboard()
            refreshDirectBrowse()
        }

        val path = currentBrowsePath ?: storagePath
        viewLifecycleOwner.lifecycleScope.launch {
            val showHidden = try { UserPreferences.showHiddenFiles } catch (_: Exception) { false }
            val listing = com.filecleaner.app.utils.file.DirectoryBrowser.listDirectory(path, showHidden)

            if (_binding == null) return@launch

            // Build browse items: parent (..) + folders + files
            val items = mutableListOf<BrowseAdapter.Item>()

            // Add parent directory entry if not at root
            if (listing.parentPath != null) {
                items.add(BrowseAdapter.Item.Folder(
                    path = listing.parentPath,
                    name = "..",
                    itemCount = 0,
                    totalSize = 0
                ))
            }

            // Add folders as proper Folder items (displayed as grid cards or list rows)
            for (folder in listing.folders) {
                items.add(BrowseAdapter.Item.Folder(
                    path = folder.path,
                    name = folder.name,
                    itemCount = folder.itemCount,
                    totalSize = folder.totalSize
                ))
            }

            // Apply category filter
            val catPos = _binding?.spinnerCategory?.selectedItemPosition ?: 0
            val selectedCat = if (catPos in categories.indices) categories[catPos].second else null

            var filteredFiles = listing.files
            // Filter by category if a specific one is selected
            if (selectedCat is com.filecleaner.app.data.FileCategory) {
                filteredFiles = filteredFiles.filter { it.category == selectedCat }
            }
            // Filter by search query
            if (searchQuery.isNotBlank()) {
                filteredFiles = filteredFiles.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
            // Filter by selected extensions
            if (selectedExtensions.isNotEmpty()) {
                filteredFiles = filteredFiles.filter { it.extension in selectedExtensions }
            }

            for (file in filteredFiles) {
                items.add(BrowseAdapter.Item.File(file))
            }

            val fileCount = items.size
            if (fileCount == 0) {
                binding.tvEmpty.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
                binding.tvEmptyText.text = getString(R.string.empty_directory)
                binding.btnScanNow.visibility = View.GONE
            } else {
                binding.tvEmpty.visibility = View.GONE
                binding.recyclerView.visibility = View.VISIBLE
            }

            adapter.submitFullList(items) {
                // Restore scroll position if we have one saved
                if (restoredScrollPos >= 0) {
                    _binding?.recyclerView?.scrollToPosition(restoredScrollPos)
                    restoredScrollPos = -1
                }
            }
            binding.tvCount.text = resources.getQuantityString(R.plurals.n_files, fileCount, fileCount)
            updateExpandCollapseButton()

            // Update breadcrumb bar
            val breadcrumbs = com.filecleaner.app.utils.file.DirectoryBrowser.getBreadcrumbs(path)
            binding.tvBrowseSubtitle?.text = breadcrumbs.joinToString(" › ") { it.first }
            binding.breadcrumbBar?.visibility = View.VISIBLE
            binding.breadcrumbBar?.setPath(breadcrumbs)
            binding.breadcrumbBar?.onSegmentClick = { segmentPath ->
                navigateToDirectory(segmentPath)
            }
        }
    }

    private fun refresh() {
        // Use direct browsing mode if active or if no scan data exists
        val hasScanData = vm.filesByCategory.value?.isNotEmpty() == true
        if (directBrowseMode || (!hasScanData && vm.scanState.value !is ScanState.Scanning)) {
            directBrowseMode = true
            if (currentBrowsePath == null) currentBrowsePath = storagePath
            refreshDirectBrowse()
            return
        }

        val catPos = binding.spinnerCategory.selectedItemPosition
        if (catPos < 0 || catPos >= categories.size) return
        val catEntry = categories[catPos]
        val selectedCat = catEntry.second

        val allFiles = vm.filesByCategory.value?.values?.flatten() ?: emptyList()
        val raw: List<FileItem> = when {
            selectedCat == null -> allFiles
            selectedCat == VIRTUAL_RECENT -> {
                val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
                allFiles.filter { it.lastModified >= cutoff }.sortedByDescending { it.lastModified }
            }
            selectedCat == VIRTUAL_FAVORITES -> {
                val favPaths = try { UserPreferences.favoritePaths } catch (_: Exception) { emptySet() }
                allFiles.filter { it.path in favPaths }
            }
            selectedCat is FileCategory -> vm.filesByCategory.value?.get(selectedCat) ?: emptyList()
            else -> allFiles
        }

        // Apply search filter (with operator support for advanced queries)
        val searched = SearchQueryParser.filterItems(raw, searchQuery)

        // Build extension chips from searched file set
        updateExtensionChips(searched)

        // Apply extension filter
        val filtered = if (selectedExtensions.isEmpty()) {
            searched
        } else {
            searched.filter { it.extension in selectedExtensions }
        }

        val sortPos = binding.spinnerSort.selectedItemPosition.coerceAtLeast(0)
        val sorted = SearchQueryParser.sortItems(filtered, sortPos)

        // Group files by parent folder and build list with section headers
        val browseItems = buildGroupedList(sorted)
        val fileCount = browseItems.count { it is BrowseAdapter.Item.File }

        // Toggle empty/list visibility BEFORE submitting items so the RecyclerView
        // measures at its correct height before items are laid out (fixes items
        // appearing too low on first scan).
        if (fileCount == 0) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            val scanState = vm.scanState.value
            val isPreScan = scanState !is ScanState.Done
            val isScanning = scanState is ScanState.Scanning
            binding.tvEmptyText.text = when {
                searchQuery.isNotEmpty() -> getString(R.string.empty_search_results, searchQuery)
                isScanning -> getString(R.string.scanning_in_progress)
                !isPreScan -> getString(R.string.empty_browse_post_scan)
                else -> getString(R.string.empty_browse_pre_scan)
            }
            binding.btnScanNow.visibility = if (isPreScan && !isScanning && searchQuery.isEmpty()) View.VISIBLE else View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }

        adapter.submitFullList(browseItems) {
            if (shouldScrollToTop) {
                _binding?.recyclerView?.scrollToPosition(0)
                shouldScrollToTop = false
            }
        }
        binding.tvCount.text = resources.getQuantityString(R.plurals.n_files, fileCount, fileCount)
        updateExpandCollapseButton()
    }

    /** Groups files by their parent folder and creates a list with folder headers. */
    private fun buildGroupedList(files: List<FileItem>): List<BrowseAdapter.Item> {
        if (files.isEmpty()) return emptyList()

        // Group by parent directory
        val grouped = files.groupBy { File(it.path).parent ?: "" }

        // Sort groups: root-level first (matching arborescence root), then by path
        val sortedGroups = grouped.entries.sortedWith(
            compareBy(
                { it.key.removePrefix(storagePath).count { c -> c == File.separatorChar } },
                { it.key.lowercase() }
            )
        )

        val result = mutableListOf<BrowseAdapter.Item>()
        for ((folderPath, folderFiles) in sortedGroups) {
            // Create a readable folder display name
            val displayName = folderDisplayName(folderPath)
            val totalSize = folderFiles.sumOf { it.size }
            result.add(BrowseAdapter.Item.Header(folderPath, displayName, folderFiles.size, totalSize))
            for (file in folderFiles) {
                result.add(BrowseAdapter.Item.File(file))
            }
        }
        return result
    }

    /** Converts a full folder path to a user-friendly display name relative to storage. */
    private fun folderDisplayName(folderPath: String): String {
        if (folderPath.isEmpty()) return "/"
        // Show path relative to external storage for readability
        val relative = if (folderPath.startsWith(storagePath)) {
            folderPath.removePrefix(storagePath)
        } else {
            folderPath
        }
        return if (relative.isEmpty()) getString(R.string.storage_root) else relative
    }

    private fun updateExtensionChips(files: List<FileItem>) {
        val chipGroup = binding.chipGroupExtensions

        // Count extensions
        val extCounts = mutableMapOf<String, Int>()
        for (file in files) {
            val ext = file.extension
            if (ext.isNotEmpty()) {
                extCounts[ext] = (extCounts[ext] ?: 0) + 1
            }
        }

        // Show top 15 extensions sorted by count
        val topExtensions = extCounts.entries
            .sortedByDescending { it.value }
            .take(15)

        if (topExtensions.isEmpty()) {
            binding.scrollExtensions.visibility = View.GONE
            return
        }

        binding.scrollExtensions.visibility = View.VISIBLE
        chipGroup.removeAllViews()

        for ((ext, count) in topExtensions) {
            val chip = Chip(requireContext()).apply {
                text = getString(R.string.extension_chip_format, ext, count)
                isCheckable = true
                isChecked = ext in selectedExtensions
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedExtensions.add(ext) else selectedExtensions.remove(ext)
                    refresh()
                }
            }
            chipGroup.addView(chip)
        }
    }

    private val contextMenuCallback by lazy {
        FileContextMenu.defaultCallback(vm,
            onMoveTo = { item -> showDirectoryPicker(item) },
            onSelect = { item -> adapter.enterSelectionMode(item.path) },
            onRefresh = ::refresh)
    }

    private fun showDirectoryPicker(item: com.filecleaner.app.data.FileItem) {
        val tree = vm.directoryTree.value ?: return
        com.filecleaner.app.ui.common.DirectoryPickerDialog.show(
            requireContext(), tree, excludePath = java.io.File(item.path).parent
        ) { targetDir ->
            vm.moveFile(item.path, targetDir)
        }
    }

    // B5: Save all user-visible state for config change survival
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_VIEW_MODE, currentViewMode.ordinal)
        outState.putString(KEY_SEARCH_QUERY, searchQuery)
        outState.putInt(KEY_SORT_ORDER, _binding?.spinnerSort?.selectedItemPosition ?: 0)
        outState.putInt(KEY_CATEGORY_POS, _binding?.spinnerCategory?.selectedItemPosition ?: 0)
        outState.putStringArrayList(KEY_EXTENSIONS, ArrayList(selectedExtensions))
        outState.putBoolean(KEY_DIRECT_BROWSE, directBrowseMode)
        outState.putString(KEY_BROWSE_PATH, currentBrowsePath)
        if (::adapter.isInitialized) {
            outState.putStringArrayList(KEY_COLLAPSED_FOLDERS, ArrayList(adapter.collapsedFolders))
            outState.putInt(KEY_FOLDER_VIEW_MODE, adapter.folderViewMode.ordinal)
        }
        // Save scroll position
        val lm = _binding?.recyclerView?.layoutManager
        if (lm is androidx.recyclerview.widget.LinearLayoutManager) {
            outState.putInt(KEY_SCROLL_POS, lm.findFirstVisibleItemPosition())
        }
    }

    // ── Saved Search Filters ──────────────────────────────────────────────

    private fun showSavedSearchesDialog() {
        val ctx = context ?: return
        val searches = SavedSearchManager.getSavedSearches(ctx)
        if (searches.isEmpty() && searchQuery.isBlank()) {
            Snackbar.make(binding.root, getString(R.string.saved_search_empty), Snackbar.LENGTH_SHORT).show()
            return
        }

        val items = mutableListOf<String>()
        if (searchQuery.isNotBlank()) {
            items.add(getString(R.string.saved_search_save_current))
        }
        for (s in searches) {
            items.add(s.name)
        }

        RoundedDialogBuilder(ctx)
            .setTitle(R.string.saved_search_title)
            .setItems(items.toTypedArray()) { _, which ->
                if (searchQuery.isNotBlank() && which == 0) {
                    showSaveSearchNameDialog()
                } else {
                    val index = if (searchQuery.isNotBlank()) which - 1 else which
                    val search = searches[index]
                    binding.etSearch.setText(search.query)
                    binding.etSearch.setSelection(search.query.length)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSaveSearchNameDialog() {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply {
            hint = getString(R.string.saved_search_name_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            val pad = resources.getDimensionPixelSize(R.dimen.spacing_xxl)
            setPadding(pad, pad, pad, resources.getDimensionPixelSize(R.dimen.spacing_sm))
        }
        RoundedDialogBuilder(ctx)
            .setTitle(R.string.saved_search_save_title)
            .setView(input)
            .setPositiveButton(R.string.saved_search_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    SavedSearchManager.saveSearch(ctx, name, searchQuery)
                    Snackbar.make(binding.root, getString(R.string.saved_search_saved, name), Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── Create New File/Folder ─────────────────────────────────────────

    private fun showCreateNewDialog() {
        val ctx = context ?: return
        val dirPath = currentBrowsePath ?: storagePath
        val items = arrayOf<CharSequence>(
            getString(R.string.create_new_file),
            getString(R.string.create_new_folder)
        )
        RoundedDialogBuilder(ctx)
            .setTitle(getString(R.string.create_new))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showCreateFileDialog(dirPath)
                    1 -> showCreateFolderDialog(dirPath)
                }
            }
            .show()
    }

    private fun showCreateFileDialog(dirPath: String) {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply {
            hint = getString(R.string.create_file_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            val pad = resources.getDimensionPixelSize(R.dimen.spacing_xxl)
            setPadding(pad, pad, pad, resources.getDimensionPixelSize(R.dimen.spacing_sm))
        }
        RoundedDialogBuilder(ctx)
            .setTitle(getString(R.string.create_new_file))
            .setView(input)
            .setPositiveButton(getString(R.string.create)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    val result = vm.fileOps.createNewFile(dirPath, name)
                    Snackbar.make(binding.root, result.message, Snackbar.LENGTH_SHORT).show()
                    if (result.success) refreshDirectBrowse()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showCreateFolderDialog(dirPath: String) {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply {
            hint = getString(R.string.create_folder_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            val pad = resources.getDimensionPixelSize(R.dimen.spacing_xxl)
            setPadding(pad, pad, pad, resources.getDimensionPixelSize(R.dimen.spacing_sm))
        }
        RoundedDialogBuilder(ctx)
            .setTitle(getString(R.string.create_new_folder))
            .setView(input)
            .setPositiveButton(getString(R.string.create)) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    val result = vm.fileOps.createNewFolder(dirPath, name)
                    Snackbar.make(binding.root, result.message, Snackbar.LENGTH_SHORT).show()
                    if (result.success) refreshDirectBrowse()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── Go To Path ──────────────────────────────────────────────────────

    private fun showGoToPathDialog() {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply {
            hint = storagePath
            setText(currentBrowsePath ?: storagePath)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            val pad = resources.getDimensionPixelSize(R.dimen.spacing_xxl)
            setPadding(pad, pad, pad, resources.getDimensionPixelSize(R.dimen.spacing_sm))
            selectAll()
        }
        RoundedDialogBuilder(ctx)
            .setTitle(getString(R.string.go_to_path))
            .setView(input)
            .setPositiveButton(getString(R.string.go)) { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotBlank() && java.io.File(path).isDirectory) {
                    navigateToDirectory(path)
                } else {
                    Snackbar.make(binding.root, getString(R.string.go_to_path_invalid), Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onDestroyView() {
        activeDialog?.dismiss()
        activeDialog = null
        searchDebounceJob?.cancel()
        searchTextWatcher?.let { binding.etSearch.removeTextChangedListener(it) }
        searchTextWatcher = null
        dragMoveListener?.let { binding.recyclerView.removeOnItemTouchListener(it) }
        dragStartListener?.let { binding.recyclerView.removeOnItemTouchListener(it) }
        dragMoveListener = null
        dragStartListener = null
        binding.spinnerCategory.onItemSelectedListener = null
        binding.spinnerSort.onItemSelectedListener = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val KEY_VIEW_MODE = "browse_view_mode"
        private const val KEY_FOLDER_VIEW_MODE = "browse_folder_view_mode"
        private const val KEY_SEARCH_QUERY = "browse_search_query"
        private const val KEY_SORT_ORDER = "browse_sort_order"
        private const val KEY_CATEGORY_POS = "browse_category_pos"
        private const val KEY_EXTENSIONS = "browse_extensions"
        private const val KEY_COLLAPSED_FOLDERS = "browse_collapsed_folders"
        private const val KEY_DIRECT_BROWSE = "browse_direct_mode"
        private const val KEY_BROWSE_PATH = "browse_path"
        private const val KEY_SCROLL_POS = "browse_scroll_pos"
    }
}
