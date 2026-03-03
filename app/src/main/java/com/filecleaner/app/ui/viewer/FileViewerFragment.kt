package com.filecleaner.app.ui.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.filecleaner.app.R
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.databinding.FragmentFileViewerBinding
import com.filecleaner.app.utils.FileOpener
import com.filecleaner.app.utils.UndoHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen file viewer supporting images (Glide), PDFs (PdfRenderer),
 * and text files (monospace scrollable view).
 */
class FileViewerFragment : Fragment() {

    companion object {
        const val ARG_FILE_PATH = "file_path"
        private const val MAX_TEXT_BYTES = 50 * 1024 // 50 KB for full viewer
        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "csv", "json", "xml", "html", "htm", "log",
            "yml", "yaml", "toml", "ini", "cfg", "conf", "properties",
            "sh", "bat", "py", "js", "ts", "kt", "java", "c", "cpp",
            "h", "css", "scss", "sql", "gradle", "gitignore"
        )
    }

    private var _binding: FragmentFileViewerBinding? = null
    private val binding get() = _binding!!

    private var pdfRenderer: PdfRenderer? = null
    private var pdfFd: ParcelFileDescriptor? = null
    private var currentPdfPage = 0

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentFileViewerBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filePath = arguments?.getString(ARG_FILE_PATH) ?: run {
            findNavController().popBackStack()
            return
        }
        val file = File(filePath)
        if (!file.exists()) {
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

        // File info bar
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        binding.tvFileInfo.text = getString(
            R.string.viewer_file_info,
            UndoHelper.formatBytes(file.length()),
            dateFmt.format(Date(file.lastModified()))
        )

        // Display content based on type
        when {
            category == FileCategory.IMAGE -> showImage(file)
            ext == "pdf" -> showPdf(file, savedInstanceState)
            ext in TEXT_EXTENSIONS -> showText(file)
            category == FileCategory.VIDEO -> showVideoFallback(file)
            else -> showUnsupported(file)
        }
    }

    private fun showImage(file: File) {
        binding.ivImage.visibility = View.VISIBLE
        Glide.with(this)
            .load(file)
            .into(binding.ivImage)
    }

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
        bitmap.eraseColor(android.graphics.Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        binding.ivPdfPage.setImageBitmap(bitmap)

        val pageCount = renderer.pageCount
        binding.tvPdfPageInfo.text = getString(R.string.viewer_pdf_page, currentPdfPage + 1, pageCount)
        binding.btnPdfPrev.isEnabled = currentPdfPage > 0
        binding.btnPdfNext.isEnabled = currentPdfPage < pageCount - 1
    }

    private fun showText(file: File) {
        binding.scrollText.visibility = View.VISIBLE
        val content = try {
            val bytes = file.inputStream().use { it.readNBytes(MAX_TEXT_BYTES) }
            val text = String(bytes, Charsets.UTF_8)
            if (file.length() > MAX_TEXT_BYTES) {
                text + "\n\n\u2026 [truncated at 50 KB]"
            } else {
                text
            }
        } catch (e: Exception) {
            getString(R.string.preview_error, e.localizedMessage ?: "")
        }
        binding.tvTextContent.text = content
    }

    private fun showVideoFallback(file: File) {
        // Show video thumbnail with a prompt to open externally
        binding.ivImage.visibility = View.VISIBLE
        Glide.with(this)
            .load(file)
            .into(binding.ivImage)
        binding.ivImage.setOnClickListener { FileOpener.open(requireContext(), file) }
    }

    private fun showUnsupported(file: File) {
        binding.unsupportedContainer.visibility = View.VISIBLE
        binding.tvUnsupported.text = getString(R.string.viewer_unsupported, file.name.substringAfterLast('.', ""))
        binding.btnOpenFallback.setOnClickListener { FileOpener.open(requireContext(), file) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("pdf_page", currentPdfPage)
    }

    override fun onDestroyView() {
        pdfRenderer?.close()
        pdfFd?.close()
        pdfRenderer = null
        pdfFd = null
        super.onDestroyView()
        _binding = null
    }
}
