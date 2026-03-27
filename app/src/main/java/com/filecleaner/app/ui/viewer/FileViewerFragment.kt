package com.filecleaner.app.ui.viewer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.filecleaner.app.R
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.databinding.FragmentFileViewerBinding
import com.filecleaner.app.utils.FileOpener
import com.filecleaner.app.utils.UndoHelper
import com.filecleaner.app.utils.applyBottomInset
import com.filecleaner.app.ui.viewer.strategy.*
import com.google.android.material.snackbar.Snackbar
import java.io.File

/**
 * Full-screen in-app file viewer. Routes content display to delegate classes
 * for each viewer type (Image, PDF, Text, Video, Audio, HTML, Markdown, APK).
 * The fragment owns the layout binding, toolbar, and lifecycle management.
 */
class FileViewerFragment : Fragment() {

    companion object {
        const val ARG_FILE_PATH = "file_path"
    }

    private var _binding: FragmentFileViewerBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler(Looper.getMainLooper())

    // Viewer delegates — initialized lazily on first use
    private var imageDelegate: ImageViewerDelegate? = null
    private var pdfDelegate: PdfViewerDelegate? = null
    private var audioDelegate: AudioPlayerDelegate? = null
    private var videoDelegate: VideoPlayerDelegate? = null
    private var textDelegate: TextEditorDelegate? = null
    private val htmlStrategy = HtmlViewerStrategy()
    private val markdownStrategy = MarkdownViewerStrategy()
    private val apkStrategy = ApkViewerStrategy()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentFileViewerBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.contentFrame.applyBottomInset()

        val filePath = arguments?.getString(ARG_FILE_PATH) ?: run {
            findNavController().popBackStack()
            return
        }
        val file = File(filePath)

        setupSwipeNavigation(file)

        if (!file.exists()) {
            Snackbar.make(binding.root, getString(R.string.op_file_not_found), Snackbar.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        val ext = file.name.substringAfterLast('.', "").lowercase()
        val category = FileCategory.fromExtension(ext)

        // Toolbar
        binding.tvFilename.text = file.name
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }
        binding.btnOpenExternal.setOnClickListener { FileOpener.open(requireContext(), file) }
        binding.btnShare.setOnClickListener { FileOpener.share(requireContext(), file) }

        // Delete from viewer
        binding.btnDeleteFile.setOnClickListener {
            com.filecleaner.app.ui.common.RoundedDialogBuilder(requireContext())
                .setTitle(getString(R.string.ctx_delete))
                .setMessage(getString(R.string.viewer_delete_confirm, file.name))
                .setPositiveButton(getString(R.string.ctx_delete)) { _, _ ->
                    if (file.delete()) {
                        Snackbar.make(binding.root, getString(R.string.viewer_deleted), Snackbar.LENGTH_SHORT).show()
                        findNavController().popBackStack()
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        // File info/properties dialog
        binding.btnFileInfo.setOnClickListener {
            val info = buildString {
                appendLine("${getString(R.string.prop_name)}: ${file.name}")
                appendLine("${getString(R.string.prop_path)}: ${file.absolutePath}")
                appendLine("${getString(R.string.prop_size)}: ${UndoHelper.formatBytes(file.length())} (${file.length()} bytes)")
                appendLine("${getString(R.string.prop_modified)}: ${com.filecleaner.app.utils.DateFormatUtils.formatDateTime(file.lastModified())}")
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "unknown"
                appendLine("${getString(R.string.prop_type)}: $mime")
                if (category == FileCategory.IMAGE) {
                    val exif = com.filecleaner.app.utils.ExifReader.read(filePath)
                    if (exif != null) {
                        appendLine("\n${com.filecleaner.app.utils.ExifReader.formatReadable(exif)}")
                    }
                }
            }
            com.filecleaner.app.ui.common.RoundedDialogBuilder(requireContext())
                .setTitle(getString(R.string.ctx_properties))
                .setMessage(info)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // File info bar
        binding.tvFileInfo.text = getString(
            R.string.viewer_file_info,
            UndoHelper.formatBytes(file.length()),
            com.filecleaner.app.utils.DateFormatUtils.formatDateTime(file.lastModified())
        )

        // Display content based on type — routed via ViewerRegistry
        when (ViewerRegistry.getViewerType(ext, category)) {
            ViewerRegistry.ViewerType.IMAGE -> showImage(file)
            ViewerRegistry.ViewerType.PDF -> showPdf(file, savedInstanceState)
            ViewerRegistry.ViewerType.TEXT -> showText(file, ext)
            ViewerRegistry.ViewerType.VIDEO -> showVideo(file)
            ViewerRegistry.ViewerType.AUDIO -> showAudio(file)
            ViewerRegistry.ViewerType.HTML -> htmlStrategy.show(file, binding.root, null)
            ViewerRegistry.ViewerType.MARKDOWN -> markdownStrategy.show(file, binding.root, null)
            ViewerRegistry.ViewerType.APK -> showApkInfo(file)
            ViewerRegistry.ViewerType.UNSUPPORTED -> showUnsupported(file)
        }
    }

    // ── Image (delegated) ───────────────────────────────────────────────────

    private fun showImage(file: File) {
        val delegate = ImageViewerDelegate(binding.ivImage, binding.root)
        imageDelegate = delegate
        delegate.show(file, this)
        delegate.setupEditBar(
            file, this, viewLifecycleOwner.lifecycleScope,
            binding.imageEditBar,
            binding.btnEditRotate, binding.btnEditFlip, binding.btnEditCrop,
            binding.btnEditBw, binding.btnEditInvert, binding.btnEditBright, binding.btnEditDark
        )
    }

    // ── PDF (delegated) ─────────────────────────────────────────────────────

    private fun showPdf(file: File, savedInstanceState: Bundle?) {
        val delegate = PdfViewerDelegate(
            binding.pdfContainer, binding.ivPdfPage, binding.tvPdfPageInfo,
            binding.btnPdfPrev, binding.btnPdfNext,
            getString(R.string.viewer_pdf_page)
        )
        pdfDelegate = delegate
        if (!delegate.show(file, savedInstanceState)) {
            showUnsupported(file)
        }
    }

    // ── Text/Code (delegated) ───────────────────────────────────────────────

    private fun showText(file: File, ext: String) {
        val delegate = TextEditorDelegate(
            requireContext(), binding.root,
            binding.codeEditorContainer, binding.codeToolbar,
            binding.tvTextContent, binding.tvLineNumbers, binding.tvCodeInfo,
            binding.btnCodeSave, binding.btnCodeUndo, binding.btnCodeRedo,
            binding.btnCodeWrap, binding.btnCodeGoLine, binding.btnCodeFind,
            binding.findBar, binding.etFindQuery,
            binding.btnFindNext, binding.btnFindPrev, binding.btnFindClose
        )
        textDelegate = delegate
        delegate.show(file, ext)
    }

    // ── Video (delegated) ───────────────────────────────────────────────────

    private fun showVideo(file: File) {
        val delegate = VideoPlayerDelegate(
            binding.videoContainer, binding.videoView,
            binding.ivVideoPlayOverlay, binding.videoControls,
            binding.btnVideoPlay, binding.seekVideo,
            binding.tvVideoCurrent, binding.tvVideoDuration,
            binding.btnVideoSpeed, handler,
            getString(R.string.a11y_play_video), getString(R.string.a11y_pause_video)
        )
        videoDelegate = delegate
        delegate.show(file) { showUnsupported(file) }
    }

    // ── Audio (delegated) ───────────────────────────────────────────────────

    private fun showAudio(file: File) {
        val delegate = AudioPlayerDelegate(
            binding.audioContainer, binding.ivAudioArt, binding.tvAudioTitle,
            binding.btnAudioPlay, binding.seekAudio,
            binding.tvAudioCurrent, binding.tvAudioDuration,
            handler,
            getString(R.string.a11y_play_audio), getString(R.string.a11y_pause_audio),
            getString(R.string.viewer_audio_error)
        )
        audioDelegate = delegate
        delegate.show(file, this)
    }

    // ── APK (delegated to ApkViewerStrategy) ────────────────────────────────

    private fun showApkInfo(file: File) {
        binding.codeEditorContainer.visibility = View.VISIBLE
        binding.codeToolbar.visibility = View.VISIBLE
        binding.btnCodeSave.text = getString(R.string.apk_install)
        binding.btnCodeSave.setOnClickListener { installApk(file) }
        binding.btnCodeUndo.visibility = View.GONE
        binding.btnCodeRedo.visibility = View.GONE
        binding.btnCodeFind.visibility = View.GONE
        binding.tvTextContent.isFocusableInTouchMode = false
        binding.tvTextContent.keyListener = null

        val pm = requireContext().packageManager
        val info = apkStrategy.analyze(file, pm)
        val contents = apkStrategy.listContents(file)
        val fullInfo = info + contents

        binding.tvTextContent.setText(fullInfo)
        val lineCount = fullInfo.count { it == '\n' } + 1
        binding.tvLineNumbers.text = (1..lineCount).joinToString("\n")
    }

    private fun installApk(file: File) {
        val ctx = requireContext()
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    // ── Swipe Navigation ────────────────────────────────────────────────────

    private fun setupSwipeNavigation(currentFile: File) {
        val parentDir = currentFile.parentFile ?: return
        val siblings = parentDir.listFiles()
            ?.filter { it.isFile && !it.isHidden }
            ?.sortedBy { it.name.lowercase() }
            ?: return
        if (siblings.size < 2) return

        val currentIndex = siblings.indexOfFirst { it.absolutePath == currentFile.absolutePath }
        if (currentIndex < 0) return

        val gestureDetector = android.view.GestureDetector(requireContext(),
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent,
                                     velocityX: Float, velocityY: Float): Boolean {
                    if (e1 == null) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (kotlin.math.abs(dx) < kotlin.math.abs(dy)) return false
                    if (kotlin.math.abs(dx) < 100 || kotlin.math.abs(velocityX) < 200) return false

                    val nextIndex = if (dx < 0) currentIndex + 1 else currentIndex - 1
                    if (nextIndex < 0 || nextIndex >= siblings.size) return false

                    val nextFile = siblings[nextIndex]
                    val bundle = Bundle().apply { putString(ARG_FILE_PATH, nextFile.absolutePath) }
                    findNavController().navigate(R.id.fileViewerFragment, bundle,
                        androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(R.id.fileViewerFragment, true)
                            .setEnterAnim(if (dx < 0) R.anim.nav_enter else R.anim.nav_pop_enter)
                            .setExitAnim(if (dx < 0) R.anim.nav_exit else R.anim.nav_pop_exit)
                            .build()
                    )
                    return true
                }
            })

        binding.contentFrame.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    // ── Unsupported Fallback ────────────────────────────────────────────────

    private fun showUnsupported(file: File) {
        binding.unsupportedContainer.visibility = View.VISIBLE
        binding.tvUnsupported.text = getString(R.string.viewer_unsupported, file.name.substringAfterLast('.', ""))
        binding.btnOpenFallback.setOnClickListener { FileOpener.open(requireContext(), file) }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pdfDelegate?.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        audioDelegate?.pause()
        videoDelegate?.pause()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        audioDelegate?.destroy()
        audioDelegate = null
        videoDelegate?.destroy()
        videoDelegate = null
        pdfDelegate?.destroy()
        pdfDelegate = null
        imageDelegate = null
        textDelegate = null
        // WebView cleanup
        _binding?.webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        super.onDestroyView()
        _binding = null
    }
}
