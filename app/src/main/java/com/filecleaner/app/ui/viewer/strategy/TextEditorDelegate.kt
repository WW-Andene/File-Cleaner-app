package com.filecleaner.app.ui.viewer.strategy

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.filecleaner.app.R
import com.filecleaner.app.ui.common.RoundedDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File

/**
 * Encapsulates text/code viewing and editing: syntax highlighting,
 * line numbers, find bar, word wrap, go-to-line, save/undo/redo.
 */
class TextEditorDelegate(
    private val context: Context,
    private val rootView: View,
    private val codeEditorContainer: View,
    private val codeToolbar: View,
    private val tvTextContent: EditText,
    private val tvLineNumbers: TextView,
    private val tvCodeInfo: TextView,
    private val btnCodeSave: View,
    private val btnCodeUndo: View,
    private val btnCodeRedo: View,
    private val btnCodeWrap: TextView?,
    private val btnCodeGoLine: View?,
    private val btnCodeFind: View,
    private val findBar: View,
    private val etFindQuery: EditText,
    private val btnFindNext: View,
    private val btnFindPrev: View,
    private val btnFindClose: View
) {

    companion object {
        private const val MAX_TEXT_BYTES = 50 * 1024  // 50 KB
        private const val MAX_CODE_BYTES = 200 * 1024 // 200 KB

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

    // Syntax colors — resolved lazily from theme resources
    private val colorKeyword by lazy { ContextCompat.getColor(context, R.color.syntaxKeyword) }
    private val colorString by lazy { ContextCompat.getColor(context, R.color.syntaxString) }
    private val colorComment by lazy { ContextCompat.getColor(context, R.color.syntaxComment) }
    private val colorNumber by lazy { ContextCompat.getColor(context, R.color.syntaxNumber) }
    private val colorType by lazy { ContextCompat.getColor(context, R.color.syntaxType) }

    private var currentFilePath: String? = null
    private var originalContent: String? = null
    private var isModified = false

    fun show(file: File, ext: String) {
        codeEditorContainer.visibility = View.VISIBLE
        currentFilePath = file.absolutePath

        val isCode = ext in ViewerRegistry.CODE_EXTENSIONS
        val maxBytes = if (isCode) MAX_CODE_BYTES else MAX_TEXT_BYTES
        val rawContent = try {
            val bytes = file.inputStream().use { it.readNBytes(maxBytes) }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            context.getString(R.string.preview_error, e.localizedMessage ?: "")
        }
        originalContent = rawContent

        codeToolbar.visibility = View.VISIBLE
        tvTextContent.isFocusableInTouchMode = true
        tvTextContent.isFocusable = true
        setupToolbar(file, ext)

        if (isCode) {
            tvTextContent.setText(applySyntaxHighlighting(rawContent, ext))
        } else {
            tvTextContent.setText(rawContent)
        }
        updateLineNumbers(rawContent)

        tvTextContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                isModified = text != originalContent
                updateLineNumbers(text)
                val lines = text.count { it == '\n' } + 1
                val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                val chars = text.length
                val countInfo = "L$lines W$words C$chars"
                tvCodeInfo.text = if (isModified) "$countInfo • ${context.getString(R.string.code_modified)}" else countInfo
            }
        })
    }

    private fun updateLineNumbers(text: String) {
        val lineCount = text.count { it == '\n' } + 1
        tvLineNumbers.text = (1..lineCount).joinToString("\n")
    }

    private fun setupToolbar(file: File, ext: String) {
        // Save
        btnCodeSave.setOnClickListener {
            val content = tvTextContent.text.toString()
            try {
                file.writeText(content)
                originalContent = content
                isModified = false
                tvCodeInfo.text = ""
                Snackbar.make(rootView, context.getString(R.string.code_saved), Snackbar.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Snackbar.make(rootView, context.getString(R.string.code_save_failed, e.localizedMessage ?: ""), Snackbar.LENGTH_LONG).show()
            }
        }

        // Undo/Redo
        btnCodeUndo.setOnClickListener { tvTextContent.onTextContextMenuItem(android.R.id.undo) }
        btnCodeRedo.setOnClickListener { tvTextContent.onTextContextMenuItem(android.R.id.redo) }

        // Word wrap toggle
        var wordWrapEnabled = false
        btnCodeWrap?.setOnClickListener {
            wordWrapEnabled = !wordWrapEnabled
            tvTextContent.setHorizontallyScrolling(!wordWrapEnabled)
            btnCodeWrap.text = if (wordWrapEnabled) context.getString(R.string.code_wrap_on) else context.getString(R.string.code_wrap_off)
        }

        // Go to line
        btnCodeGoLine?.setOnClickListener {
            val input = EditText(context).apply {
                hint = context.getString(R.string.code_go_line_hint)
                inputType = InputType.TYPE_CLASS_NUMBER
                val pad = context.resources.getDimensionPixelSize(R.dimen.spacing_xxl)
                setPadding(pad, pad, pad, context.resources.getDimensionPixelSize(R.dimen.spacing_sm))
            }
            RoundedDialogBuilder(context)
                .setTitle(context.getString(R.string.code_go_line))
                .setView(input)
                .setPositiveButton(context.getString(R.string.go)) { _, _ ->
                    val lineNum = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                    val text = tvTextContent.text.toString()
                    var pos = 0; var cur = 1
                    for (char in text) {
                        if (cur >= lineNum) break
                        if (char == '\n') cur++
                        pos++
                    }
                    if (pos <= text.length) {
                        tvTextContent.setSelection(pos.coerceAtMost(text.length))
                        tvTextContent.requestFocus()
                    }
                }
                .setNegativeButton(context.getString(R.string.cancel), null)
                .show()
        }

        // Find bar
        setupFindBar()
    }

    private fun setupFindBar() {
        btnCodeFind.setOnClickListener {
            findBar.visibility = if (findBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (findBar.visibility == View.VISIBLE) etFindQuery.requestFocus()
        }
        btnFindClose.setOnClickListener { findBar.visibility = View.GONE }

        var findIndex = -1
        val findMatches = mutableListOf<Int>()

        fun updateMatches() {
            val query = etFindQuery.text.toString()
            findMatches.clear(); findIndex = -1
            if (query.isBlank()) return
            val text = tvTextContent.text.toString()
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
            tvTextContent.setSelection(pos, pos + etFindQuery.text.length)
            tvTextContent.requestFocus()
        }

        etFindQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateMatches(); if (findMatches.isNotEmpty()) goToMatch(0) }
        })
        btnFindNext.setOnClickListener { updateMatches(); goToMatch(findIndex + 1) }
        btnFindPrev.setOnClickListener { updateMatches(); goToMatch(findIndex - 1) }
    }

    /**
     * Applies basic syntax highlighting. Highlights comments, strings,
     * keywords, numbers, and PascalCase type names.
     */
    fun applySyntaxHighlighting(code: String, ext: String): SpannableStringBuilder {
        val spannable = SpannableStringBuilder(code)
        val length = code.length

        val lineCommentPrefix = when (ext) {
            "py", "rb", "r", "sh", "bash", "zsh", "fish", "pl", "pm",
            "yml", "yaml", "toml", "conf", "cfg", "ini", "dockerfile",
            "makefile", "cmake", "tf", "hcl", "nix" -> "#"
            "lua", "hs", "sql" -> "--"
            "html", "htm", "xml", "svg", "plist" -> null
            else -> "//"
        }
        val hasBlockComments = ext !in setOf(
            "py", "rb", "sh", "bash", "zsh", "fish", "yml", "yaml",
            "toml", "conf", "cfg", "ini", "makefile"
        )

        val highlighted = BooleanArray(length)
        var i = 0

        while (i < length) {
            // Block comments /* ... */
            if (hasBlockComments && i + 1 < length && code[i] == '/' && code[i + 1] == '*') {
                val start = i; i += 2
                while (i + 1 < length && !(code[i] == '*' && code[i + 1] == '/')) i++
                if (i + 1 < length) i += 2
                val end = i.coerceAtMost(length)
                spannable.setSpan(ForegroundColorSpan(colorComment), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                for (j in start until end) highlighted[j] = true
                continue
            }

            // XML/HTML comments <!-- ... -->
            if (ext in setOf("html", "htm", "xml", "svg", "plist") &&
                i + 3 < length && code.substring(i).startsWith("<!--")) {
                val start = i
                val endIdx = code.indexOf("-->", i + 4)
                i = if (endIdx >= 0) endIdx + 3 else length
                spannable.setSpan(ForegroundColorSpan(colorComment), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                for (j in start until i) highlighted[j] = true
                continue
            }

            // Line comments
            if (lineCommentPrefix != null && code.startsWith(lineCommentPrefix, i)) {
                val start = i
                while (i < length && code[i] != '\n') i++
                spannable.setSpan(ForegroundColorSpan(colorComment), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                for (j in start until i) highlighted[j] = true
                continue
            }

            // String literals (double-quoted)
            if (code[i] == '"') {
                val start = i; i++
                while (i < length && code[i] != '"') { if (code[i] == '\\' && i + 1 < length) i++; i++ }
                if (i < length) i++
                spannable.setSpan(ForegroundColorSpan(colorString), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                for (j in start until i) highlighted[j] = true
                continue
            }

            // String literals (single-quoted)
            if (code[i] == '\'') {
                val start = i; i++
                while (i < length && code[i] != '\'') { if (code[i] == '\\' && i + 1 < length) i++; i++ }
                if (i < length) i++
                spannable.setSpan(ForegroundColorSpan(colorString), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
                    spannable.setSpan(ForegroundColorSpan(colorNumber), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    for (j in start until i) highlighted[j] = true
                }
                continue
            }

            // Keywords and type identifiers
            if (code[i].isLetter() || code[i] == '_') {
                val start = i
                while (i < length && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                val word = code.substring(start, i)
                if (word in COMMON_KEYWORDS) {
                    spannable.setSpan(ForegroundColorSpan(colorKeyword), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    for (j in start until i) highlighted[j] = true
                } else if (word.first().isUpperCase() && word.length > 1) {
                    spannable.setSpan(ForegroundColorSpan(colorType), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    for (j in start until i) highlighted[j] = true
                }
                continue
            }
            i++
        }
        return spannable
    }
}
