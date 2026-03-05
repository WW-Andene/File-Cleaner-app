package com.filecleaner.app.ui.adapters

enum class ViewMode(val spanCount: Int) {
    LIST_COMPACT(1),
    LIST(1),
    LIST_WITH_THUMBNAILS(1),
    GRID_TINY(6),
    GRID_SMALL(5),
    GRID_MEDIUM(4),
    GRID_LARGE(3),
    GRID_XLARGE(2),
    GRID_FULL(1);     // Full-width card with large preview

    val isGrid: Boolean get() = spanCount > 1 || this == GRID_FULL

    companion object {
        val GRID_MODES = setOf(GRID_TINY, GRID_SMALL, GRID_MEDIUM, GRID_LARGE, GRID_XLARGE, GRID_FULL)
    }
}
