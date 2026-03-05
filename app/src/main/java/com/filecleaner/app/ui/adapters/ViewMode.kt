package com.filecleaner.app.ui.adapters

/**
 * 5 visual styles × 5 sizes = 25 view modes.
 *
 * - **COMPACT**: Dense single-line rows, tiny icon, horizontal text.
 * - **LIST**: Standard two-line card, medium icon with padding.
 * - **THUMBNAIL**: Two-line card with rich media previews (album art, APK icons, real thumbnails).
 * - **GRID**: Multi-column card grid with image on top.
 * - **GALLERY**: Full-width preview cards (single column, large images).
 */
enum class ViewMode(val spanCount: Int) {
    // Compact: dense single-line list
    COMPACT_XS(1), COMPACT_SM(1), COMPACT_MD(1), COMPACT_LG(1), COMPACT_XL(1),
    // List: standard card list with category icons
    LIST_XS(1), LIST_SM(1), LIST_MD(1), LIST_LG(1), LIST_XL(1),
    // Thumbnail: list with rich media previews
    THUMB_XS(1), THUMB_SM(1), THUMB_MD(1), THUMB_LG(1), THUMB_XL(1),
    // Grid: multi-column card grid
    GRID_XS(6), GRID_SM(5), GRID_MD(4), GRID_LG(3), GRID_XL(2),
    // Gallery: full-width preview cards
    GALLERY_XS(1), GALLERY_SM(1), GALLERY_MD(1), GALLERY_LG(1), GALLERY_XL(1);

    enum class Style { COMPACT, LIST, THUMBNAIL, GRID, GALLERY }
    enum class Size { XS, SM, MD, LG, XL }

    val style: Style get() = when {
        name.startsWith("COMPACT") -> Style.COMPACT
        name.startsWith("LIST") -> Style.LIST
        name.startsWith("THUMB") -> Style.THUMBNAIL
        name.startsWith("GRID") -> Style.GRID
        name.startsWith("GALLERY") -> Style.GALLERY
        else -> Style.LIST
    }

    val size: Size get() = when {
        name.endsWith("_XS") -> Size.XS
        name.endsWith("_SM") -> Size.SM
        name.endsWith("_MD") -> Size.MD
        name.endsWith("_LG") -> Size.LG
        name.endsWith("_XL") -> Size.XL
        else -> Size.MD
    }

    /** True for modes that use the grid card layout (item_file_grid.xml). */
    val usesGridLayout: Boolean get() = style == Style.GRID || style == Style.GALLERY

    /** True for modes that load rich thumbnails (real images, album art, APK icons). */
    val showsRichThumbnails: Boolean get() =
        style == Style.THUMBNAIL || style == Style.GRID || style == Style.GALLERY

    /** Icon/container size in dp for list-based modes (COMPACT, LIST, THUMBNAIL). */
    val iconSizeDp: Int get() = when (style) {
        Style.COMPACT -> when (size) {
            Size.XS -> 16; Size.SM -> 20; Size.MD -> 28; Size.LG -> 36; Size.XL -> 44
        }
        Style.LIST -> when (size) {
            Size.XS -> 32; Size.SM -> 36; Size.MD -> 44; Size.LG -> 52; Size.XL -> 60
        }
        Style.THUMBNAIL -> when (size) {
            Size.XS -> 44; Size.SM -> 56; Size.MD -> 72; Size.LG -> 88; Size.XL -> 108
        }
        else -> 0  // grid/gallery don't use icon sizing
    }

    /** Thumbnail min height in dp for gallery mode. */
    val galleryMinHeightDp: Int get() = when (size) {
        Size.XS -> 100; Size.SM -> 140; Size.MD -> 180; Size.LG -> 240; Size.XL -> 320
    }

    companion object {
        /** All modes that use the grid card layout (for getItemViewType). */
        val GRID_LAYOUT_MODES: Set<ViewMode> = entries.filter { it.usesGridLayout }.toSet()

        /** Grid-only modes (multi-column, for layout manager). */
        val GRID_MODES: Set<ViewMode> = entries.filter { it.style == Style.GRID }.toSet()

        /** Get the mode for a given style + size combination. */
        fun of(style: Style, size: Size): ViewMode =
            entries.first { it.style == style && it.size == size }
    }
}
