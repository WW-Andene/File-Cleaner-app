package com.filecleaner.app.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.bumptech.glide.Glide
import com.filecleaner.app.R
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.databinding.FragmentFileViewerBinding
import com.filecleaner.app.utils.FileOpener
import com.filecleaner.app.utils.UndoHelper
import com.filecleaner.app.utils.applyBottomInset
import com.filecleaner.app.utils.file.FileConverter
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen in-app file viewer supporting:
 * - Images (Glide with pinch-to-zoom and pan, including GIF animation)
 * - PDFs (PdfRenderer with page navigation)
 * - Text/code files (monospace scrollable view with syntax highlighting for common languages)
 * - Video (native VideoView with play/pause/seek controls)
 * - Audio (MediaPlayer with play/pause/seek and album art)
 * - HTML/Markdown (WebView rendering)
 * - Fallback: offer to open with external app
 */
class FileViewerFragment : Fragment() {

    companion object {
        const val ARG_FILE_PATH = "file_path"
        private const val MAX_TEXT_BYTES = 50 * 1024 // 50 KB

        private val TEXT_EXTENSIONS = setOf(
            // Plain text & config
            "txt", "csv", "log", "ini", "cfg", "conf", "properties",
            "yml", "yaml", "toml", "env", "gitignore", "dockerignore",
            "editorconfig", "htaccess", "npmrc", "nvmrc",
            // Markup (non-rendered)
            "xml", "svg", "plist",
            // Code: JVM
            "kt", "kts", "java", "groovy", "gradle", "scala",
            // Code: Web
            "js", "jsx", "ts", "tsx", "css", "scss", "sass", "less",
            "vue", "svelte",
            // Code: Systems
            "c", "cpp", "cc", "cxx", "h", "hpp", "hxx",
            "rs", "go", "zig",
            // Code: Scripting
            "py", "rb", "php", "pl", "pm", "lua", "r",
            "sh", "bash", "zsh", "fish", "bat", "ps1", "cmd",
            // Code: Mobile / other
            "swift", "m", "mm", "dart",
            // Code: Functional / ML
            "hs", "ml", "ex", "exs", "erl", "clj",
            // Data & query
            "sql", "graphql", "gql", "proto",
            // Build & CI
            "makefile", "cmake", "dockerfile",
            "tf", "hcl", "nix",
            // Docs & text formats
            "tex", "latex", "bib", "srt", "sub", "ass", "vtt",
            "diff", "patch",
            // Other
            "json", "json5", "jsonc", "jsonl"
        )

        private val HTML_EXTENSIONS = setOf("html", "htm")
        private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")

        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "aac", "flac", "wav", "ogg", "m4a", "wma", "opus",
            "aiff", "mid", "amr", "ape", "wv", "m4b", "dsf"
        )

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm",
            "m4v", "3gp", "ts", "mpeg", "mpg"
        )

        // Extensions that get syntax highlighting
        private val CODE_EXTENSIONS = setOf(
            "kt", "kts", "java", "groovy", "gradle", "scala",
            "js", "jsx", "ts", "tsx", "css", "scss", "sass", "less",
            "vue", "svelte",
            "c", "cpp", "cc", "cxx", "h", "hpp", "hxx",
            "rs", "go", "zig",
            "py", "rb", "php", "pl", "pm", "lua", "r",
            "sh", "bash", "zsh", "fish", "bat", "ps1", "cmd",
            "swift", "m", "mm", "dart",
            "hs", "ml", "ex", "exs", "erl", "clj",
            "sql", "graphql", "gql", "proto",
            "xml", "svg", "plist", "json", "json5", "jsonc", "jsonl",
            "html", "htm", "css", "scss"
        )

        // Syntax highlighting colors — resolved at runtime from theme resources
        // (see values/colors.xml and values-night/colors.xml)

        // Common keywords across many languages
        private val COMMON_KEYWORDS = setOf(
            "if", "else", "for", "while", "do", "switch", "case", "break",
            "continue", "return", "try", "catch", "finally", "throw", "throws",
            "class", "interface", "object", "enum", "struct", "trait",
            "fun", "function", "def", "fn", "func", "val", "var", "let", "const",
            "import", "package", "from", "export", "module", "require",
            "public", "private", "protected", "internal", "static", "final",
            "abstract", "override", "open", "sealed", "data",
            "new", "this", "self", "super", "null", "nil", "None",
            "true", "false", "True", "False",
            "void", "int", "long", "float", "double", "boolean", "bool",
            "string", "String", "char", "byte", "short",
            "async", "await", "yield", "suspend",
            "when", "is", "as", "in", "not", "and", "or",
            "type", "typealias", "typedef", "impl", "use", "mod",
            "lambda", "inline", "extern", "unsafe", "where",
            "select", "from", "where", "insert", "update", "delete", "create",
            "table", "index", "view", "alter", "drop"
        )
    }

    private var _binding: FragmentFileViewerBinding? = null
    private val binding get() = _binding!!

    // PDF state
    private var pdfRenderer: PdfRenderer? = null
    private var pdfFd: ParcelFileDescriptor? = null
    private var currentPdfPage = 0
    private var currentPdfBitmap: Bitmap? = null

    // Audio state
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isAudioPlaying = false

    // Video state
    private var isVideoPlaying = false
    private var isVideoInitialized = false

    // Syntax highlighting colors — resolved lazily from themed resources
    private val COLOR_KEYWORD by lazy { ContextCompat.getColor(requireContext(), R.color.syntaxKeyword) }
    private val COLOR_STRING by lazy { ContextCompat.getColor(requireContext(), R.color.syntaxString) }
    private val COLOR_COMMENT by lazy { ContextCompat.getColor(requireContext(), R.color.syntaxComment) }
    private val COLOR_NUMBER by lazy { ContextCompat.getColor(requireContext(), R.color.syntaxNumber) }
    private val COLOR_TYPE by lazy { ContextCompat.getColor(requireContext(), R.color.syntaxType) }

    // Image zoom/pan state
    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentFileViewerBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Apply bottom inset to scrollable content area
        binding.contentFrame.applyBottomInset()

        val filePath = arguments?.getString(ARG_FILE_PATH) ?: run {
            findNavController().popBackStack()
            return
        }
        val file = File(filePath)

        // Swipe left/right to navigate between files in same directory
        setupSwipeNavigation(file)
        if (!file.exists()) {
            Snackbar.make(binding.root,
                getString(R.string.op_file_not_found),
                Snackbar.LENGTH_SHORT
            ).show()
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
                        com.google.android.material.snackbar.Snackbar.make(
                            binding.root, getString(R.string.viewer_deleted), com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                        ).show()
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

        // File info bar — F-081: Use centralized date formatting
        binding.tvFileInfo.text = getString(
            R.string.viewer_file_info,
            UndoHelper.formatBytes(file.length()),
            com.filecleaner.app.utils.DateFormatUtils.formatDateTime(file.lastModified())
        )

        // Display content based on type
        when {
            category == FileCategory.AUDIO || ext in AUDIO_EXTENSIONS -> showAudio(file)
            ext in HTML_EXTENSIONS -> showHtml(file)
            ext in MARKDOWN_EXTENSIONS -> showMarkdown(file)
            category == FileCategory.IMAGE -> showImage(file)
            ext == "pdf" -> showPdf(file, savedInstanceState)
            category == FileCategory.VIDEO || ext in VIDEO_EXTENSIONS -> showVideo(file)
            ext in TEXT_EXTENSIONS -> showText(file, ext)
            ext == "apk" -> showApkInfo(file)
            else -> showUnsupported(file)
        }
    }

    // ── Image Viewer with Zoom/Pan ──────────────────────────────────────────

    private fun showImage(file: File) {
        binding.ivImage.visibility = View.VISIBLE
        Glide.with(this)
            .load(file)
            .placeholder(R.drawable.ic_image)
            .error(R.drawable.ic_image)
            .into(binding.ivImage)
        binding.ivImage.contentDescription = getString(R.string.a11y_image_preview, file.name)

        setupImageZoom()
        setupImageEditBar(file)
    }

    private fun setupImageEditBar(file: File) {
        binding.imageEditBar?.visibility = View.VISIBLE

        fun applyAndReload(action: () -> FileConverter.ConvertResult) {
            viewLifecycleOwner.lifecycleScope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { action() }
                if (result.success && _binding != null) {
                    Glide.with(this@FileViewerFragment).load(java.io.File(result.outputPath)).into(binding.ivImage)
                    com.google.android.material.snackbar.Snackbar.make(
                        binding.root, result.message, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
        }

        binding.btnEditRotate?.setOnClickListener { applyAndReload { FileConverter.rotateImage(file.absolutePath, 90f) } }
        binding.btnEditFlip?.setOnClickListener { applyAndReload { FileConverter.flipImage(file.absolutePath, true) } }
        binding.btnEditCrop?.setOnClickListener { applyAndReload { FileConverter.cropToAspectRatio(file.absolutePath, 1, 1) } }
        binding.btnEditBw?.setOnClickListener { applyAndReload { FileConverter.toGrayscale(file.absolutePath) } }
        binding.btnEditInvert?.setOnClickListener { applyAndReload { FileConverter.invertColors(file.absolutePath) } }
        binding.btnEditBright?.setOnClickListener { applyAndReload { FileConverter.adjustBrightness(file.absolutePath, 1.3f) } }
        binding.btnEditDark?.setOnClickListener { applyAndReload { FileConverter.adjustBrightness(file.absolutePath, 0.7f) } }
    }

    private fun setupImageZoom() {
        val imageView = binding.ivImage
        val scaleDetector = ScaleGestureDetector(requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scaleFactor *= detector.scaleFactor
                    scaleFactor = scaleFactor.coerceIn(0.5f, 5.0f)
                    imageView.scaleX = scaleFactor
                    imageView.scaleY = scaleFactor
                    return true
                }
            })

        imageView.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    activePointerId = event.getPointerId(0)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scaleFactor > 1.0f) {
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (pointerIndex >= 0) {
                            val x = event.getX(pointerIndex)
                            val y = event.getY(pointerIndex)
                            val dx = x - lastTouchX
                            val dy = y - lastTouchY

                            translateX += dx
                            translateY += dy
                            imageView.translationX = translateX
                            imageView.translationY = translateY

                            lastTouchX = x
                            lastTouchY = y
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    if (pointerId == activePointerId) {
                        val newPointerIndex = if (pointerIndex == 0) 1 else 0
                        if (newPointerIndex < event.pointerCount) {
                            lastTouchX = event.getX(newPointerIndex)
                            lastTouchY = event.getY(newPointerIndex)
                            activePointerId = event.getPointerId(newPointerIndex)
                        }
                    }
                }
            }

            // Double-tap to reset zoom
            v.performClick()
            true
        }

        // Double-tap to reset
        imageView.setOnClickListener {
            if (scaleFactor != 1.0f) {
                scaleFactor = 1.0f
                translateX = 0f
                translateY = 0f
                imageView.scaleX = 1.0f
                imageView.scaleY = 1.0f
                imageView.translationX = 0f
                imageView.translationY = 0f
            }
        }
    }

    // ── PDF Viewer ──────────────────────────────────────────────────────────

    private fun showPdf(file: File, savedInstanceState: Bundle?) {
        binding.pdfContainer.visibility = View.VISIBLE
        try {
            pdfFd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(pdfFd!!)

            currentPdfPage = savedInstanceState?.getInt("pdf_page", 0) ?: 0
            renderPdfPage()

            binding.btnPdfPrev.setOnClickListener {
                if (currentPdfPage > 0) {
                    currentPdfPage--
                    renderPdfPage()
                }
            }
            binding.btnPdfNext.setOnClickListener {
                val pageCount = pdfRenderer?.pageCount ?: 0
                if (currentPdfPage < pageCount - 1) {
                    currentPdfPage++
                    renderPdfPage()
                }
            }

            // PDF pinch-to-zoom — reuse image zoom on the PDF ImageView
            setupPdfZoom()
        } catch (e: Exception) {
            binding.pdfContainer.visibility = View.GONE
            showUnsupported(file)
        }
    }

    private fun renderPdfPage() {
        val renderer = pdfRenderer ?: return
        val page = renderer.openPage(currentPdfPage)
        val scale = 2 // Render at 2x for readability
        val bitmap = Bitmap.createBitmap(
            page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888
        )
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()

        currentPdfBitmap?.recycle()
        currentPdfBitmap = bitmap
        binding.ivPdfPage.setImageBitmap(bitmap)

        val pageCount = renderer.pageCount
        binding.tvPdfPageInfo.text = getString(R.string.viewer_pdf_page, currentPdfPage + 1, pageCount)
        binding.btnPdfPrev.isEnabled = currentPdfPage > 0
        binding.btnPdfNext.isEnabled = currentPdfPage < pageCount - 1
    }

    /** Pinch-to-zoom for PDF pages — same concept as image zoom. */
    private fun setupPdfZoom() {
        val iv = binding.ivPdfPage ?: return
        var scaleFactor = 1f
        val scaleDetector = android.view.ScaleGestureDetector(requireContext(),
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    scaleFactor *= detector.scaleFactor
                    scaleFactor = scaleFactor.coerceIn(0.5f, 4f)
                    iv.scaleX = scaleFactor
                    iv.scaleY = scaleFactor
                    return true
                }
            })
        iv.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                v.performClick()
            }
            true
        }
    }

    // ── Text/Code Viewer with Syntax Highlighting & Editing ──────────────

    private var currentFilePath: String? = null
    private var originalContent: String? = null
    private var isCodeModified = false

    private fun showText(file: File, ext: String) {
        binding.codeEditorContainer.visibility = View.VISIBLE
        currentFilePath = file.absolutePath

        val isCode = ext in CODE_EXTENSIONS
        val maxBytes = if (isCode) MAX_TEXT_BYTES * 4 else MAX_TEXT_BYTES // 200 KB for code files
        val rawContent = try {
            val bytes = file.inputStream().use { it.readNBytes(maxBytes) }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            getString(R.string.preview_error, e.localizedMessage ?: "")
        }
        originalContent = rawContent

        // All text files are editable — code files get syntax highlighting
        binding.codeToolbar.visibility = View.VISIBLE
        binding.tvTextContent.isFocusableInTouchMode = true
        binding.tvTextContent.isFocusable = true
        setupCodeToolbar(file, ext)

        // Apply syntax highlighting and line numbers
        if (isCode) {
            binding.tvTextContent.setText(applySyntaxHighlighting(rawContent, ext))
        } else {
            binding.tvTextContent.setText(rawContent)
        }

        updateLineNumbers(rawContent)

        // Track modifications
        binding.tvTextContent.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString() ?: ""
                isCodeModified = text != originalContent
                updateLineNumbers(text)
                // Show line/word/char count + modified indicator
                val lines = text.count { it == '\n' } + 1
                val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                val chars = text.length
                val countInfo = "L$lines W$words C$chars"
                binding.tvCodeInfo.text = if (isCodeModified) "$countInfo • ${getString(R.string.code_modified)}" else countInfo
            }
        })
    }

    private fun updateLineNumbers(text: String) {
        val lineCount = text.count { it == '\n' } + 1
        val numbers = (1..lineCount).joinToString("\n")
        binding.tvLineNumbers.text = numbers
    }

    private fun setupCodeToolbar(file: File, ext: String) {
        // Save button
        binding.btnCodeSave.setOnClickListener {
            val content = binding.tvTextContent.text.toString()
            try {
                file.writeText(content)
                originalContent = content
                isCodeModified = false
                binding.tvCodeInfo.text = ""
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root, getString(R.string.code_saved),
                    com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root, getString(R.string.code_save_failed, e.localizedMessage ?: ""),
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).show()
            }
        }

        // Undo/Redo (using EditText's built-in undo)
        binding.btnCodeUndo.setOnClickListener {
            binding.tvTextContent.onTextContextMenuItem(android.R.id.undo)
        }
        binding.btnCodeRedo.setOnClickListener {
            binding.tvTextContent.onTextContextMenuItem(android.R.id.redo)
        }

        // Word wrap toggle
        var wordWrapEnabled = false
        binding.btnCodeWrap?.setOnClickListener {
            wordWrapEnabled = !wordWrapEnabled
            binding.tvTextContent.setHorizontallyScrolling(!wordWrapEnabled)
            binding.btnCodeWrap?.text = if (wordWrapEnabled) getString(R.string.code_wrap_on) else getString(R.string.code_wrap_off)
        }

        // Go to line
        binding.btnCodeGoLine?.setOnClickListener {
            val input = android.widget.EditText(requireContext()).apply {
                hint = getString(R.string.code_go_line_hint)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                val pad = resources.getDimensionPixelSize(R.dimen.spacing_xxl)
                setPadding(pad, pad, pad, resources.getDimensionPixelSize(R.dimen.spacing_sm))
            }
            com.filecleaner.app.ui.common.RoundedDialogBuilder(requireContext())
                .setTitle(getString(R.string.code_go_line))
                .setView(input)
                .setPositiveButton(getString(R.string.go)) { _, _ ->
                    val lineNum = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                    val text = binding.tvTextContent.text.toString()
                    var pos = 0
                    var currentLine = 1
                    for (char in text) {
                        if (currentLine >= lineNum) break
                        if (char == '\n') currentLine++
                        pos++
                    }
                    if (pos <= text.length) {
                        binding.tvTextContent.setSelection(pos.coerceAtMost(text.length))
                        binding.tvTextContent.requestFocus()
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }

        // Find bar
        binding.btnCodeFind.setOnClickListener {
            binding.findBar.visibility = if (binding.findBar.visibility == View.VISIBLE)
                View.GONE else View.VISIBLE
            if (binding.findBar.visibility == View.VISIBLE) {
                binding.etFindQuery.requestFocus()
            }
        }
        binding.btnFindClose.setOnClickListener {
            binding.findBar.visibility = View.GONE
        }

        var findIndex = -1
        val findMatches = mutableListOf<Int>()

        fun updateFindMatches() {
            val query = binding.etFindQuery.text.toString()
            findMatches.clear()
            findIndex = -1
            if (query.isBlank()) return
            val text = binding.tvTextContent.text.toString()
            var idx = text.indexOf(query, ignoreCase = true)
            while (idx >= 0) {
                findMatches.add(idx)
                idx = text.indexOf(query, idx + 1, ignoreCase = true)
            }
        }

        fun goToMatch(index: Int) {
            if (findMatches.isEmpty()) return
            findIndex = index.mod(findMatches.size)
            val pos = findMatches[findIndex]
            binding.tvTextContent.setSelection(pos, pos + binding.etFindQuery.text.length)
            binding.tvTextContent.requestFocus()
        }

        binding.etFindQuery.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateFindMatches()
                if (findMatches.isNotEmpty()) goToMatch(0)
            }
        })
        binding.btnFindNext.setOnClickListener {
            updateFindMatches()
            goToMatch(findIndex + 1)
        }
        binding.btnFindPrev.setOnClickListener {
            updateFindMatches()
            goToMatch(findIndex - 1)
        }
    }

    /**
     * Applies basic syntax highlighting to code text. Highlights:
     * - Single-line comments (// and #)
     * - Multi-line comments
     * - String literals (double and single quotes)
     * - Keywords
     * - Numbers
     */
    private fun applySyntaxHighlighting(code: String, ext: String): SpannableStringBuilder {
        val spannable = SpannableStringBuilder(code)
        val length = code.length

        // Determine comment style based on language
        val lineCommentPrefix = when (ext) {
            "py", "rb", "r", "sh", "bash", "zsh", "fish", "pl", "pm",
            "yml", "yaml", "toml", "conf", "cfg", "ini", "dockerfile",
            "makefile", "cmake", "tf", "hcl", "nix" -> "#"
            "lua", "hs", "sql" -> "--"
            "html", "htm", "xml", "svg", "plist" -> null // no line comments
            else -> "//"
        }
        val hasBlockComments = ext !in setOf(
            "py", "rb", "sh", "bash", "zsh", "fish", "yml", "yaml",
            "toml", "conf", "cfg", "ini", "makefile"
        )

        // Track which character positions are already highlighted
        val highlighted = BooleanArray(length)

        var i = 0
        while (i < length) {
            // Block comments: /* ... */
            if (hasBlockComments && i + 1 < length && code[i] == '/' && code[i + 1] == '*') {
                val start = i
                i += 2
                while (i + 1 < length && !(code[i] == '*' && code[i + 1] == '/')) i++
                if (i + 1 < length) i += 2
                val end = i.coerceAtMost(length)
                spannable.setSpan(
                    ForegroundColorSpan(COLOR_COMMENT), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                for (j in start until end) highlighted[j] = true
                continue
            }

            // XML/HTML comments: <!-- ... -->
            if (ext in setOf("html", "htm", "xml", "svg", "plist") &&
                i + 3 < length && code.substring(i).startsWith("<!--")) {
                val start = i
                val endIdx = code.indexOf("-->", i + 4)
                i = if (endIdx >= 0) endIdx + 3 else length
                spannable.setSpan(
                    ForegroundColorSpan(COLOR_COMMENT), start, i,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                for (j in start until i) highlighted[j] = true
                continue
            }

            // Line comments
            if (lineCommentPrefix != null && code.startsWith(lineCommentPrefix, i)) {
                val start = i
                while (i < length && code[i] != '\n') i++
                spannable.setSpan(
                    ForegroundColorSpan(COLOR_COMMENT), start, i,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                for (j in start until i) highlighted[j] = true
                continue
            }

            // String literals (double-quoted)
            if (code[i] == '"') {
                val start = i
                i++
                while (i < length && code[i] != '"') {
                    if (code[i] == '\\' && i + 1 < length) i++ // skip escaped char
                    i++
                }
                if (i < length) i++ // closing quote
                spannable.setSpan(
                    ForegroundColorSpan(COLOR_STRING), start, i,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                for (j in start until i) highlighted[j] = true
                continue
            }

            // String literals (single-quoted)
            if (code[i] == '\'') {
                val start = i
                i++
                while (i < length && code[i] != '\'') {
                    if (code[i] == '\\' && i + 1 < length) i++
                    i++
                }
                if (i < length) i++
                spannable.setSpan(
                    ForegroundColorSpan(COLOR_STRING), start, i,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                for (j in start until i) highlighted[j] = true
                continue
            }

            // Numbers
            if (code[i].isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit())) {
                val start = i
                while (i < length && (code[i].isDigit() || code[i] == '.' ||
                            code[i] == 'x' || code[i] == 'f' || code[i] == 'L' ||
                            (code[i] in 'a'..'f') || (code[i] in 'A'..'F'))) i++
                if (!highlighted.sliceArray(start until i.coerceAtMost(length)).any { it }) {
                    spannable.setSpan(
                        ForegroundColorSpan(COLOR_NUMBER), start, i,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    for (j in start until i) highlighted[j] = true
                }
                continue
            }

            // Keywords and identifiers
            if (code[i].isLetter() || code[i] == '_') {
                val start = i
                while (i < length && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                val word = code.substring(start, i)
                if (word in COMMON_KEYWORDS) {
                    spannable.setSpan(
                        ForegroundColorSpan(COLOR_KEYWORD), start, i,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    for (j in start until i) highlighted[j] = true
                } else if (word.first().isUpperCase() && word.length > 1) {
                    // Type names (PascalCase)
                    spannable.setSpan(
                        ForegroundColorSpan(COLOR_TYPE), start, i,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    for (j in start until i) highlighted[j] = true
                }
                continue
            }

            i++
        }

        return spannable
    }

    // ── Video Player ────────────────────────────────────────────────────────

    private fun showVideo(file: File) {
        binding.videoContainer.visibility = View.VISIBLE
        val videoView = binding.videoView
        val playOverlay = binding.ivVideoPlayOverlay
        val controls = binding.videoControls

        videoView.setVideoURI(Uri.fromFile(file))

        // Show thumbnail first via Glide on the play overlay background
        videoView.setOnPreparedListener { mp ->
            isVideoInitialized = true
            binding.seekVideo.max = mp.duration
            binding.tvVideoDuration.text = formatTime(mp.duration)
            binding.tvVideoCurrent.text = formatTime(0)

            mp.setOnCompletionListener {
                isVideoPlaying = false
                _binding?.btnVideoPlay?.setImageResource(android.R.drawable.ic_media_play)
                _binding?.ivVideoPlayOverlay?.visibility = View.VISIBLE
                _binding?.videoControls?.visibility = View.GONE
                _binding?.seekVideo?.progress = 0
                _binding?.tvVideoCurrent?.text = formatTime(0)
            }
        }

        videoView.setOnErrorListener { _, _, _ ->
            _binding?.videoContainer?.visibility = View.GONE
            showUnsupported(file)
            true
        }

        // Tap play overlay to start video
        playOverlay.setOnClickListener {
            playOverlay.visibility = View.GONE
            controls.visibility = View.VISIBLE
            videoView.start()
            isVideoPlaying = true
            binding.btnVideoPlay.setImageResource(android.R.drawable.ic_media_pause)
            updateVideoSeekBar()
        }

        // Tap video to toggle controls visibility
        videoView.setOnClickListener {
            if (isVideoInitialized) {
                controls.visibility = if (controls.visibility == View.VISIBLE)
                    View.GONE else View.VISIBLE
            }
        }

        // Play/pause button
        binding.btnVideoPlay.contentDescription = getString(R.string.a11y_play_video)
        binding.btnVideoPlay.setOnClickListener {
            if (isVideoPlaying) {
                videoView.pause()
                isVideoPlaying = false
                binding.btnVideoPlay.setImageResource(android.R.drawable.ic_media_play)
                binding.btnVideoPlay.contentDescription = getString(R.string.a11y_play_video)
            } else {
                videoView.start()
                isVideoPlaying = true
                binding.btnVideoPlay.setImageResource(android.R.drawable.ic_media_pause)
                binding.btnVideoPlay.contentDescription = getString(R.string.a11y_pause_video)
                updateVideoSeekBar()
            }
        }

        // Seek bar
        binding.seekVideo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    videoView.seekTo(progress)
                    _binding?.tvVideoCurrent?.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Video speed control
        val speeds = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f)
        val speedLabels = arrayOf("0.5x", "1x", "1.5x", "2x")
        var speedIndex = 1 // Default 1x
        binding.btnVideoSpeed?.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            val speed = speeds[speedIndex]
            binding.btnVideoSpeed?.text = speedLabels[speedIndex]
            try {
                val params = android.media.PlaybackParams().setSpeed(speed)
                videoView.setPlaybackParams(params)
            } catch (_: Exception) { /* Some devices don't support speed change */ }
        }
    }

    private fun updateVideoSeekBar() {
        if (_binding == null) return // Guard against post-destruction callback
        if (isVideoPlaying && isVideoInitialized) {
            try {
                val pos = _binding?.videoView?.currentPosition ?: return
                _binding?.seekVideo?.progress = pos
                _binding?.tvVideoCurrent?.text = formatTime(pos)
            } catch (_: Exception) { }
            handler.postDelayed({ updateVideoSeekBar() }, 500)
        }
    }

    // ── Audio Player ────────────────────────────────────────────────────────

    private fun showAudio(file: File) {
        binding.audioContainer.visibility = View.VISIBLE

        // Try to extract album art
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val art = retriever.embeddedPicture
            if (art != null) {
                Glide.with(this).load(art)
                    .placeholder(R.drawable.ic_audio)
                    .error(R.drawable.ic_audio)
                    .into(binding.ivAudioArt)
            }
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            binding.tvAudioTitle.text = title ?: file.nameWithoutExtension
        } catch (e: Exception) {
            binding.tvAudioTitle.text = file.nameWithoutExtension
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
            }
            val mp = mediaPlayer!!
            binding.seekAudio.max = mp.duration
            binding.tvAudioDuration.text = formatTime(mp.duration)
            binding.tvAudioCurrent.text = formatTime(0)

            binding.btnAudioPlay.contentDescription = getString(R.string.a11y_play_audio)
            binding.btnAudioPlay.setOnClickListener {
                if (isAudioPlaying) {
                    mp.pause()
                    isAudioPlaying = false
                    binding.btnAudioPlay.setImageResource(android.R.drawable.ic_media_play)
                    binding.btnAudioPlay.contentDescription = getString(R.string.a11y_play_audio)
                } else {
                    mp.start()
                    isAudioPlaying = true
                    binding.btnAudioPlay.setImageResource(android.R.drawable.ic_media_pause)
                    binding.btnAudioPlay.contentDescription = getString(R.string.a11y_pause_audio)
                    updateAudioSeekBar()
                }
            }

            binding.seekAudio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        mp.seekTo(progress)
                        _binding?.tvAudioCurrent?.text = formatTime(progress)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            mp.setOnCompletionListener {
                isAudioPlaying = false
                _binding?.btnAudioPlay?.setImageResource(android.R.drawable.ic_media_play)
                _binding?.btnAudioPlay?.contentDescription = getString(R.string.a11y_play_audio)
                _binding?.seekAudio?.progress = 0
                _binding?.tvAudioCurrent?.text = formatTime(0)
            }
        } catch (e: Exception) {
            _binding?.tvAudioTitle?.text = getString(R.string.viewer_audio_error)
        }
    }

    private fun updateAudioSeekBar() {
        if (_binding == null) return
        val mp = mediaPlayer ?: return
        if (isAudioPlaying && mp.isPlaying) {
            _binding?.seekAudio?.progress = mp.currentPosition
            _binding?.tvAudioCurrent?.text = formatTime(mp.currentPosition)
            handler.postDelayed({ updateAudioSeekBar() }, 500)
        }
    }

    // ── HTML Viewer ─────────────────────────────────────────────────────────

    private fun showHtml(file: File) {
        binding.webView.visibility = View.VISIBLE
        binding.webView.settings.apply {
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
        }
        // Load file content as data URL to avoid granting file:// access.
        // Wrap in a CSP meta tag to block scripts, forms, and external resources.
        val rawHtml = file.readText()
        val cspMeta = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:;\">"
        val headRegex = Regex("(<head[^>]*>)", RegexOption.IGNORE_CASE)
        val htmlContent = if (rawHtml.contains("<head", ignoreCase = true)) {
            headRegex.replaceFirst(rawHtml, "$1$cspMeta")
        } else {
            "$cspMeta$rawHtml"
        }
        binding.webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
        binding.webView.contentDescription = getString(R.string.a11y_webview_content, file.name)
    }

    // ── Markdown Viewer ─────────────────────────────────────────────────────

    private fun showMarkdown(file: File) {
        binding.webView.visibility = View.VISIBLE
        binding.webView.settings.apply {
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }

        val content = try {
            val bytes = file.inputStream().use { it.readNBytes(MAX_TEXT_BYTES) }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            "Error reading file: ${e.localizedMessage}"
        }

        val html = buildString {
            append("<!DOCTYPE html><html><head>")
            append("<meta charset='utf-8'>")
            append("<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:;\">")
            append("<meta name='viewport' content='width=device-width, initial-scale=1'>")
            append("<style>")
            // Light mode defaults
            append(":root{--text:#1C1E1C;--bg:#F8F6F2;--code-bg:#EEEAE4;--border:#DDD9D3;--muted:#626966;}")
            // Dark mode overrides via system preference
            append("@media(prefers-color-scheme:dark){:root{--text:#E6E4DF;--bg:#0E1311;--code-bg:#1F2723;--border:#2D3531;--muted:#8A918D;}}")
            append("body{font-family:sans-serif;padding:16px;line-height:1.6;color:var(--text);background:var(--bg);max-width:100%;word-wrap:break-word;}")
            append("pre,code{background:var(--code-bg);padding:2px 6px;border-radius:4px;font-family:monospace;font-size:14px;}")
            append("pre{padding:12px;overflow-x:auto;}")
            append("pre code{background:none;padding:0;}")
            append("blockquote{border-left:4px solid var(--border);margin:0;padding:0 16px;color:var(--muted);}")
            append("h1,h2,h3{margin-top:24px;}")
            append("hr{border:none;border-top:1px solid var(--border);margin:24px 0;}")
            append("img{max-width:100%;}")
            append("table{border-collapse:collapse;width:100%;}")
            append("th,td{border:1px solid var(--border);padding:8px;text-align:left;}")
            append("th{background:var(--code-bg);}")
            append("</style></head><body>")

            var inCodeBlock = false
            for (line in content.lines()) {
                if (line.trimStart().startsWith("```")) {
                    if (inCodeBlock) {
                        append("</code></pre>")
                        inCodeBlock = false
                    } else {
                        append("<pre><code>")
                        inCodeBlock = true
                    }
                    continue
                }
                if (inCodeBlock) {
                    append(line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                    append("\n")
                    continue
                }

                var processed = line
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

                // Headers
                processed = processed
                    .replace(Regex("^######\\s+(.*)"), "<h6>$1</h6>")
                    .replace(Regex("^#####\\s+(.*)"), "<h5>$1</h5>")
                    .replace(Regex("^####\\s+(.*)"), "<h4>$1</h4>")
                    .replace(Regex("^###\\s+(.*)"), "<h3>$1</h3>")
                    .replace(Regex("^##\\s+(.*)"), "<h2>$1</h2>")
                    .replace(Regex("^#\\s+(.*)"), "<h1>$1</h1>")

                // Bold & italic
                processed = processed
                    .replace(Regex("\\*\\*\\*(.+?)\\*\\*\\*"), "<b><i>$1</i></b>")
                    .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
                    .replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")

                // Inline code
                processed = processed.replace(Regex("`(.+?)`"), "<code>$1</code>")

                // Images ![alt](url)
                processed = processed.replace(Regex("!\\[([^]]*)]\\(([^)]+)\\)"), "<img src=\"$2\" alt=\"$1\">")

                // Links [text](url)
                processed = processed.replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")

                // Horizontal rule
                if (processed.matches(Regex("^\\s*[-*_]{3,}\\s*$"))) {
                    append("<hr>")
                    continue
                }

                // Blockquote
                if (processed.startsWith("&gt; ")) {
                    processed = "<blockquote>${processed.removePrefix("&gt; ")}</blockquote>"
                }

                // Unordered list
                if (processed.matches(Regex("^\\s*[-*+]\\s+.*"))) {
                    processed = "<li>${processed.replace(Regex("^\\s*[-*+]\\s+"), "")}</li>"
                }

                // Ordered list
                if (processed.matches(Regex("^\\s*\\d+\\.\\s+.*"))) {
                    processed = "<li>${processed.replace(Regex("^\\s*\\d+\\.\\s+"), "")}</li>"
                }

                if (processed.isBlank()) {
                    append("<br>")
                } else if (!processed.startsWith("<h") && !processed.startsWith("<li") &&
                    !processed.startsWith("<blockquote") && !processed.startsWith("<hr")) {
                    append("<p>$processed</p>")
                } else {
                    append(processed)
                }
            }
            if (inCodeBlock) append("</code></pre>")
            append("</body></html>")
        }

        // Use null base URL to prevent local file access (consistent with showHtml)
        binding.webView.loadDataWithBaseURL(
            null,
            html,
            "text/html",
            "UTF-8",
            null
        )
        binding.webView.contentDescription = getString(R.string.a11y_webview_content, file.name)
    }

    // ── APK Viewer ────────────────────────────────────────────────────────

    private fun showApkInfo(file: File) {
        binding.codeEditorContainer.visibility = View.VISIBLE
        // Show toolbar with Install button instead of save
        binding.codeToolbar.visibility = View.VISIBLE
        binding.btnCodeSave.text = getString(R.string.apk_install)
        binding.btnCodeSave.setOnClickListener { installApk(file) }
        binding.btnCodeUndo.visibility = View.GONE
        binding.btnCodeRedo.visibility = View.GONE
        binding.btnCodeFind.visibility = View.GONE
        binding.tvTextContent.isFocusableInTouchMode = false
        binding.tvTextContent.keyListener = null

        val ctx = requireContext()
        val pm = ctx.packageManager

        try {
            val apkInfo = pm.getPackageArchiveInfo(file.absolutePath,
                android.content.pm.PackageManager.GET_PERMISSIONS or
                android.content.pm.PackageManager.GET_ACTIVITIES or
                android.content.pm.PackageManager.GET_META_DATA
            )

            if (apkInfo == null) {
                binding.tvTextContent.setText(getString(R.string.apk_parse_failed))
                return
            }

            val appInfo = apkInfo.applicationInfo
            appInfo?.sourceDir = file.absolutePath
            appInfo?.publicSourceDir = file.absolutePath

            val appName = try { appInfo?.let { pm.getApplicationLabel(it).toString() } ?: "Unknown" } catch (_: Exception) { "Unknown" }
            val versionName = apkInfo.versionName ?: "Unknown"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                apkInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                apkInfo.versionCode.toString()
            }
            val packageName = apkInfo.packageName ?: "Unknown"
            val minSdk = appInfo?.minSdkVersion ?: 0
            val targetSdk = appInfo?.targetSdkVersion ?: 0

            // Check if already installed
            val installedVersion = try {
                val installed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, 0)
                }
                installed.versionName ?: "Unknown"
            } catch (_: Exception) { null }

            // Permissions
            val permissions = apkInfo.requestedPermissions ?: emptyArray()
            val dangerousPerms = permissions.filter { perm ->
                try {
                    val permInfo = pm.getPermissionInfo(perm, 0)
                    permInfo.protection == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
                } catch (_: Exception) { false }
            }

            val info = buildString {
                appendLine("══════════════════════════════")
                appendLine("  APK DETAILS")
                appendLine("══════════════════════════════")
                appendLine()
                appendLine("App Name:      $appName")
                appendLine("Package:       $packageName")
                appendLine("Version:       $versionName ($versionCode)")
                appendLine("Min SDK:       $minSdk (Android ${sdkToVersion(minSdk)})")
                appendLine("Target SDK:    $targetSdk (Android ${sdkToVersion(targetSdk)})")
                appendLine("File Size:     ${UndoHelper.formatBytes(file.length())}")
                appendLine()

                if (installedVersion != null) {
                    appendLine("⚠ Already installed: v$installedVersion")
                    if (installedVersion != versionName) {
                        appendLine("  APK is ${if (versionName > installedVersion) "NEWER" else "OLDER"} than installed")
                    }
                    appendLine()
                }

                appendLine("══════════════════════════════")
                appendLine("  PERMISSIONS (${permissions.size})")
                appendLine("══════════════════════════════")
                appendLine()

                if (dangerousPerms.isNotEmpty()) {
                    appendLine("⚠ Dangerous permissions (${dangerousPerms.size}):")
                    for (perm in dangerousPerms) {
                        appendLine("  • ${perm.substringAfterLast('.')}")
                    }
                    appendLine()
                }

                appendLine("All permissions:")
                for (perm in permissions.sorted()) {
                    val short = perm.substringAfterLast('.')
                    val isDangerous = perm in dangerousPerms
                    appendLine("  ${if (isDangerous) "⚠" else "•"} $short")
                }

                // Activities count
                val activities = apkInfo.activities?.size ?: 0
                if (activities > 0) {
                    appendLine()
                    appendLine("Activities: $activities")
                }
            }

            val fullInfo = info + showApkContents(file)
            binding.tvTextContent.setText(fullInfo)
            updateLineNumbers(fullInfo)

            // Try to load APK icon
            try {
                val icon = appInfo?.let { pm.getApplicationIcon(it) }
                if (icon != null) {
                    // Show icon in image viewer area as well
                    binding.tvLineNumbers.visibility = View.GONE
                }
            } catch (_: Exception) { }

        } catch (e: Exception) {
            binding.tvTextContent.setText(getString(R.string.apk_parse_failed) + "\n\n${e.localizedMessage}")
        }
    }

    private fun sdkToVersion(sdk: Int): String = when (sdk) {
        29 -> "10"
        30 -> "11"
        31 -> "12"
        32 -> "12L"
        33 -> "13"
        34 -> "14"
        35 -> "15"
        else -> if (sdk > 0) "API $sdk" else "?"
    }

    /** Install an APK via system installer. */
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

    /** List contents of an APK (ZIP archive) and show as browsable tree. */
    private fun showApkContents(file: File): String {
        return try {
            val entries = mutableListOf<Pair<String, Long>>() // name to size
            java.util.zip.ZipInputStream(file.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        entries.add(entry.name to entry.size)
                    }
                    entry = zis.nextEntry
                }
            }

            val grouped = entries.groupBy { it.first.substringBefore('/') }
            buildString {
                appendLine()
                appendLine("══════════════════════════════")
                appendLine("  APK CONTENTS (${entries.size} files)")
                appendLine("══════════════════════════════")
                appendLine()

                for ((folder, files) in grouped.toSortedMap()) {
                    val folderSize = files.sumOf { it.second }
                    appendLine("📁 $folder/ (${UndoHelper.formatBytes(folderSize)})")
                    // Show first 10 files per folder, then "... and N more"
                    for ((idx, f) in files.sortedBy { it.first }.withIndex()) {
                        if (idx >= 10) {
                            appendLine("     ... and ${files.size - 10} more files")
                            break
                        }
                        val name = f.first.substringAfter('/')
                        appendLine("   📄 $name (${UndoHelper.formatBytes(f.second)})")
                    }
                }

                // Summary
                appendLine()
                appendLine("Total: ${entries.size} files, ${UndoHelper.formatBytes(entries.sumOf { it.second })}")
            }
        } catch (e: Exception) {
            "\n\nCannot read APK contents: ${e.localizedMessage}"
        }
    }

    // ── Swipe Navigation ──────────────────────────────────────────────────

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
                    if (kotlin.math.abs(dx) < kotlin.math.abs(dy)) return false // Vertical swipe
                    if (kotlin.math.abs(dx) < 100 || kotlin.math.abs(velocityX) < 200) return false

                    val nextIndex = if (dx < 0) currentIndex + 1 else currentIndex - 1
                    if (nextIndex < 0 || nextIndex >= siblings.size) return false

                    val nextFile = siblings[nextIndex]
                    // Navigate to next file by replacing fragment arguments
                    val bundle = android.os.Bundle().apply { putString(ARG_FILE_PATH, nextFile.absolutePath) }
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
            false // Don't consume — let child views handle too
        }
    }

    // ── Unsupported Fallback ────────────────────────────────────────────────

    private fun showUnsupported(file: File) {
        binding.unsupportedContainer.visibility = View.VISIBLE
        binding.tvUnsupported.text = getString(R.string.viewer_unsupported, file.name.substringAfterLast('.', ""))
        binding.btnOpenFallback.setOnClickListener { FileOpener.open(requireContext(), file) }
    }

    // ── Utility ─────────────────────────────────────────────────────────────

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("pdf_page", currentPdfPage)
    }

    override fun onPause() {
        super.onPause()
        // Pause audio if playing
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                isAudioPlaying = false
                _binding?.btnAudioPlay?.setImageResource(android.R.drawable.ic_media_play)
            }
        }
        // Pause video if playing
        if (isVideoPlaying) {
            _binding?.videoView?.pause()
            isVideoPlaying = false
            _binding?.btnVideoPlay?.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        // Release audio
        mediaPlayer?.setOnCompletionListener(null)
        mediaPlayer?.release()
        mediaPlayer = null
        isAudioPlaying = false
        // Release video — clear all listeners before stopping to prevent stale callbacks
        _binding?.videoView?.setOnPreparedListener(null)
        _binding?.videoView?.setOnErrorListener(null)
        _binding?.videoView?.setOnCompletionListener(null)
        _binding?.videoView?.stopPlayback()
        isVideoPlaying = false
        isVideoInitialized = false
        // Release PDF
        currentPdfBitmap?.recycle()
        currentPdfBitmap = null
        pdfRenderer?.close()
        pdfFd?.close()
        pdfRenderer = null
        pdfFd = null
        // WebView cleanup
        _binding?.webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        super.onDestroyView()
        _binding = null
    }
}
