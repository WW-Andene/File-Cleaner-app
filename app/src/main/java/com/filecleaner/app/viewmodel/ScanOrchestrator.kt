package com.filecleaner.app.viewmodel

import android.app.Application
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.filecleaner.app.R
import com.filecleaner.app.data.DirectoryNode
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem
import com.filecleaner.app.data.UserPreferences
import com.filecleaner.app.utils.file.DuplicateFinder
import com.filecleaner.app.utils.file.FileScanner
import com.filecleaner.app.utils.file.JunkFinder
import com.filecleaner.app.utils.system.AppBadgeManager
import com.filecleaner.app.utils.file.ScanCache
import com.filecleaner.app.utils.analytics.StorageHistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Extracted scan orchestration logic from MainViewModel.
 * Manages the 4-phase scan pipeline (indexing → duplicates → analyzing → junk)
 * and exposes scan results as LiveData.
 *
 * This is a delegate, not a ViewModel — MainViewModel holds a reference
 * to avoid changing 12 fragments' activityViewModels() calls.
 */
class ScanOrchestrator(private val app: Application, private val storagePath: String) {

    companion object {
        fun pruneOrphanDuplicates(dupes: List<FileItem>): List<FileItem> {
            val validGroups = dupes.groupBy { it.duplicateGroup }
                .filter { it.value.size >= 2 }.keys
            return dupes.filter { it.duplicateGroup in validGroups }
        }
    }

    private val _scanState = MutableLiveData<ScanState>(ScanState.Idle)
    val scanState: LiveData<ScanState> = _scanState

    private val _filesByCategory = MutableLiveData<Map<FileCategory, List<FileItem>>>(emptyMap())
    val filesByCategory: LiveData<Map<FileCategory, List<FileItem>>> = _filesByCategory

    private val _duplicates = MutableLiveData<List<FileItem>>(emptyList())
    val duplicates: LiveData<List<FileItem>> = _duplicates

    private val _largeFiles = MutableLiveData<List<FileItem>>(emptyList())
    val largeFiles: LiveData<List<FileItem>> = _largeFiles

    private val _junkFiles = MutableLiveData<List<FileItem>>(emptyList())
    val junkFiles: LiveData<List<FileItem>> = _junkFiles

    private val _storageStats = MutableLiveData<MainViewModel.StorageStats>()
    val storageStats: LiveData<MainViewModel.StorageStats> = _storageStats

    private val _directoryTree = MutableLiveData<DirectoryNode?>()
    val directoryTree: LiveData<DirectoryNode?> = _directoryTree

    var scanJob: Job? = null
    val stateMutex = Mutex()

    @Volatile
    var isScanning = false
        private set

    // In-memory state for cache persistence
    var latestFiles: List<FileItem> = emptyList()
    var latestTree: DirectoryNode? = null

    private fun str(id: Int): String = app.getString(id)

    fun setScanState(state: ScanState) { _scanState.value = state }
    fun postScanState(state: ScanState) { _scanState.postValue(state) }
    fun setIsScanning(value: Boolean) { isScanning = value }

    fun postResults(
        files: List<FileItem>,
        tree: DirectoryNode,
        dupes: List<FileItem>,
        large: List<FileItem>,
        junk: List<FileItem>,
        scanDurationMs: Long
    ) {
        _directoryTree.postValue(tree)
        _filesByCategory.postValue(files.groupBy { it.category })
        _duplicates.postValue(dupes)
        _largeFiles.postValue(large)
        _junkFiles.postValue(junk)

        var totalSize = 0L
        for (item in files) totalSize += item.size
        var junkSize = 0L
        for (item in junk) junkSize += item.size
        var dupSize = 0L
        for (item in dupes) dupSize += item.size
        var largeSize = 0L
        for (item in large) largeSize += item.size

        _storageStats.postValue(MainViewModel.StorageStats(
            totalFiles = files.size,
            totalSize = totalSize,
            junkSize = junkSize,
            duplicateSize = dupSize,
            largeSize = largeSize,
            scanDurationMs = scanDurationMs
        ))
    }

    fun resetDerivedState() {
        _duplicates.value = emptyList()
        _largeFiles.value = emptyList()
        _junkFiles.value = emptyList()
    }

    fun updateAfterDelete(
        remaining: List<FileItem>,
        dupes: List<FileItem>,
        large: List<FileItem>,
        junk: List<FileItem>
    ) {
        _filesByCategory.postValue(remaining.groupBy { it.category })
        _duplicates.postValue(dupes)
        _largeFiles.postValue(large)
        _junkFiles.postValue(junk)
        recalcStats(remaining, dupes, large, junk)
    }

    fun recalcStats(
        remaining: List<FileItem>,
        dupes: List<FileItem>,
        large: List<FileItem>,
        junk: List<FileItem>
    ) {
        var totalSize = 0L
        for (item in remaining) totalSize += item.size
        var junkSize = 0L
        for (item in junk) junkSize += item.size
        var dupSize = 0L
        for (item in dupes) dupSize += item.size
        var largeSize = 0L
        for (item in large) largeSize += item.size

        _storageStats.postValue(MainViewModel.StorageStats(
            totalFiles = remaining.size,
            totalSize = totalSize,
            junkSize = junkSize,
            duplicateSize = dupSize,
            largeSize = largeSize
        ))
    }
}
