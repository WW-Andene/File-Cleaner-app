package com.filecleaner.app.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.filecleaner.app.data.FileCategory
import com.filecleaner.app.data.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Detects visually similar photos using perceptual hashing (pHash).
 *
 * Unlike DuplicateFinder which finds byte-identical files, this finds
 * photos that look similar but may differ in resolution, compression,
 * or slight cropping. Useful for cleaning burst shots, screenshots of
 * the same screen, or resaved photos.
 *
 * Algorithm:
 * 1. Resize image to 8x8 grayscale
 * 2. Compute average brightness
 * 3. Generate 64-bit hash: 1 if pixel > average, 0 otherwise
 * 4. Compare hashes with Hamming distance — threshold of 10 = similar
 */
object SimilarPhotoDetector {

    private const val HASH_SIZE = 8 // 8x8 = 64-bit hash
    private const val SIMILARITY_THRESHOLD = 10 // Hamming distance threshold

    data class SimilarGroup(
        val groupId: Int,
        val photos: List<FileItem>,
        val totalSize: Long
    )

    /**
     * Finds groups of visually similar photos from the given file list.
     * Only processes IMAGE category files.
     *
     * @param files All scanned files (non-images are filtered out)
     * @param onProgress Called with (processed, total) for progress updates
     * @return Groups of similar photos sorted by total group size (largest first)
     */
    suspend fun findSimilarPhotos(
        files: List<FileItem>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): List<SimilarGroup> = withContext(Dispatchers.IO) {
        val images = files.filter { it.category == FileCategory.IMAGE }
        if (images.size < 2) return@withContext emptyList()

        // Phase 1: Compute perceptual hashes
        val hashes = mutableListOf<Pair<FileItem, Long>>()
        for ((index, item) in images.withIndex()) {
            ensureActive()
            if (index % 10 == 0) onProgress?.invoke(index, images.size)

            val hash = computePerceptualHash(item.path)
            if (hash != null) {
                hashes.add(item to hash)
            }
        }
        onProgress?.invoke(images.size, images.size)

        // Phase 2: Group by similarity (O(n²) but bounded by image count)
        val used = BooleanArray(hashes.size)
        val groups = mutableListOf<SimilarGroup>()
        var groupId = 0

        for (i in hashes.indices) {
            if (used[i]) continue
            val group = mutableListOf(hashes[i].first)
            used[i] = true

            for (j in i + 1 until hashes.size) {
                if (used[j]) continue
                val distance = hammingDistance(hashes[i].second, hashes[j].second)
                if (distance <= SIMILARITY_THRESHOLD) {
                    group.add(hashes[j].first)
                    used[j] = true
                }
            }

            if (group.size >= 2) {
                groups.add(SimilarGroup(
                    groupId = groupId++,
                    photos = group.sortedByDescending { it.size },
                    totalSize = group.sumOf { it.size }
                ))
            }
        }

        groups.sortedByDescending { it.totalSize }
    }

    /**
     * Computes a 64-bit perceptual hash for an image file.
     * Returns null if the image can't be decoded.
     */
    private fun computePerceptualHash(path: String): Long? {
        return try {
            // Decode at reduced size to save memory
            val options = BitmapFactory.Options().apply {
                inSampleSize = 8 // Rough downscale first
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val rough = BitmapFactory.decodeFile(path, options) ?: return null

            // Scale to exact 8x8
            val small = Bitmap.createScaledBitmap(rough, HASH_SIZE, HASH_SIZE, true)
            if (small !== rough) rough.recycle()

            // Convert to grayscale and compute average
            val pixels = IntArray(HASH_SIZE * HASH_SIZE)
            small.getPixels(pixels, 0, HASH_SIZE, 0, 0, HASH_SIZE, HASH_SIZE)
            small.recycle()

            val gray = IntArray(pixels.size) { i ->
                val c = pixels[i]
                (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            }

            val average = gray.sum() / gray.size

            // Build 64-bit hash
            var hash = 0L
            for (i in gray.indices) {
                if (gray[i] > average) {
                    hash = hash or (1L shl i)
                }
            }
            hash
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** Hamming distance between two 64-bit hashes. */
    private fun hammingDistance(a: Long, b: Long): Int {
        return java.lang.Long.bitCount(a xor b)
    }
}
