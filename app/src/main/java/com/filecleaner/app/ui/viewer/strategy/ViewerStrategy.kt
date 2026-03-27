package com.filecleaner.app.ui.viewer.strategy

import android.os.Bundle
import android.view.View
import java.io.File

/**
 * Strategy interface for file viewers. Each file type (image, PDF, video,
 * audio, text, HTML, markdown, APK) implements this interface.
 *
 * FileViewerFragment delegates to the appropriate strategy based on
 * file extension/category. This decouples format-specific logic from
 * the fragment lifecycle.
 *
 * Future: each strategy can be extracted to its own file for independent
 * testing and modification without touching other formats.
 */
interface ViewerStrategy {

    /** Returns true if this strategy can handle the given file extension. */
    fun canHandle(extension: String, category: com.filecleaner.app.data.FileCategory): Boolean

    /** Show the file content. Called from onViewCreated. */
    fun show(file: File, rootView: View, savedInstanceState: Bundle?)

    /** Clean up resources. Called from onDestroyView. */
    fun destroy() {}

    /** Save state for configuration changes. */
    fun saveState(outState: Bundle) {}
}
