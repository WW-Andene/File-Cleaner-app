package com.filecleaner.app.ui.viewer.strategy

import com.filecleaner.app.data.FileCategory

/**
 * Registry that maps file types to viewer strategies.
 * Used by FileViewerFragment to find the right strategy for a file.
 *
 * Currently returns the strategy name for documentation purposes —
 * the actual routing is still in FileViewerFragment.onViewCreated().
 * This registry will be the single source of truth once all
 * strategies are extracted from the fragment.
 */
object ViewerRegistry {

    /** File extensions handled by each viewer. */
    val TEXT_EXTENSIONS = setOf(
        "txt", "csv", "log", "ini", "cfg", "conf", "properties",
        "yml", "yaml", "toml", "env", "gitignore", "dockerignore",
        "editorconfig", "htaccess", "npmrc", "nvmrc",
        "xml", "svg", "plist",
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
        "makefile", "cmake", "dockerfile",
        "tf", "hcl", "nix",
        "tex", "latex", "bib", "srt", "sub", "ass", "vtt",
        "diff", "patch",
        "json", "json5", "jsonc", "jsonl"
    )

    val CODE_EXTENSIONS = setOf(
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

    val HTML_EXTENSIONS = setOf("html", "htm")
    val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")

    val AUDIO_EXTENSIONS = setOf(
        "mp3", "aac", "flac", "wav", "ogg", "m4a", "wma", "opus",
        "aiff", "mid", "amr", "ape", "wv", "m4b", "dsf"
    )

    val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm",
        "m4v", "3gp", "ts", "mpeg", "mpg"
    )

    /** Determine which viewer type handles a given file. */
    fun getViewerType(extension: String, category: FileCategory): ViewerType {
        return when {
            category == FileCategory.AUDIO || extension in AUDIO_EXTENSIONS -> ViewerType.AUDIO
            extension in HTML_EXTENSIONS -> ViewerType.HTML
            extension in MARKDOWN_EXTENSIONS -> ViewerType.MARKDOWN
            category == FileCategory.IMAGE -> ViewerType.IMAGE
            extension == "pdf" -> ViewerType.PDF
            category == FileCategory.VIDEO || extension in VIDEO_EXTENSIONS -> ViewerType.VIDEO
            extension in TEXT_EXTENSIONS -> ViewerType.TEXT
            extension == "apk" -> ViewerType.APK
            else -> ViewerType.UNSUPPORTED
        }
    }

    enum class ViewerType {
        IMAGE, PDF, TEXT, VIDEO, AUDIO, HTML, MARKDOWN, APK, UNSUPPORTED
    }
}
