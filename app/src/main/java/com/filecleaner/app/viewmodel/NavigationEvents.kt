package com.filecleaner.app.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * I5-01: Extracted from MainViewModel to reduce god-class responsibilities.
 * Manages navigation events for Browse tab filtering and tree highlighting.
 */
class NavigationEvents {

    // Navigate to Browse tab (filtered to a folder)
    private val _navigateToBrowse = MutableLiveData<String?>()
    val navigateToBrowse: LiveData<String?> = _navigateToBrowse

    fun requestBrowseFolder(folderPath: String) {
        _navigateToBrowse.value = folderPath
    }

    fun clearBrowseNavigation() {
        _navigateToBrowse.value = null
    }

    // Navigate to tree highlight
    private val _navigateToTree = MutableLiveData<String?>()
    val navigateToTree: LiveData<String?> = _navigateToTree

    fun requestTreeHighlight(filePath: String) {
        _navigateToTree.value = filePath
    }

    fun clearTreeHighlight() {
        _navigateToTree.value = null
    }
}
