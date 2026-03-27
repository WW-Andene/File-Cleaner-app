package com.filecleaner.app.utils.file

import android.graphics.Bitmap
import java.io.OutputStream

/**
 * GIF89a animated encoder using only Android SDK APIs.
 *
 * Features:
 * - Median-cut color quantization (256-color palette from actual image colors)
 * - Floyd-Steinberg dithering for smooth gradients
 * - Standard LZW compression
 * - Netscape looping extension for animation
 */
class SimpleGifEncoder(private val out: OutputStream) {

    private var width = 0
    private var height = 0
    private var delayCs = 10
    private var palette = IntArray(256)
    private var started = false
    private var headerWritten = false

    fun start(w: Int, h: Int, delayCentiseconds: Int = 10) {
        width = w
        height = h
        delayCs = delayCentiseconds
        started = true
    }

    fun addFrame(bitmap: Bitmap) {
        val scaled = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else bitmap

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()

        if (!headerWritten) {
            palette = medianCutQuantize(pixels, 256)
            writeHeader()
            writeGlobalColorTable()
            writeNetscapeExtension()
            headerWritten = true
        }

        val indexed = ditherAndQuantize(pixels)
        writeGraphicControlExtension()
        writeImageDescriptor()
        writeLzwCompressed(indexed)
    }

    fun finish() {
        if (headerWritten) {
            out.write(0x3B)
            out.flush()
        }
    }

    // ── Median-cut color quantization ──────────────────────────────

    private fun medianCutQuantize(pixels: IntArray, numColors: Int): IntArray {
        // Sample pixels (every Nth for large images to save memory)
        val step = (pixels.size / 50000).coerceAtLeast(1)
        val samples = mutableListOf<IntArray>()
        for (i in pixels.indices step step) {
            val c = pixels[i]
            samples.add(intArrayOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF))
        }

        // Median-cut: recursively split color boxes
        val boxes = mutableListOf(samples)
        while (boxes.size < numColors && boxes.isNotEmpty()) {
            // Find the box with the largest range on any channel
            var bestIdx = 0
            var bestRange = 0
            var bestChannel = 0
            for (i in boxes.indices) {
                val box = boxes[i]
                if (box.size < 2) continue
                for (ch in 0..2) {
                    var mn = 255; var mx = 0
                    for (c in box) { mn = minOf(mn, c[ch]); mx = maxOf(mx, c[ch]) }
                    val range = mx - mn
                    if (range > bestRange) { bestRange = range; bestIdx = i; bestChannel = ch }
                }
            }
            if (bestRange == 0) break

            val box = boxes.removeAt(bestIdx)
            box.sortBy { it[bestChannel] }
            val mid = box.size / 2
            boxes.add(box.subList(0, mid).toMutableList())
            boxes.add(box.subList(mid, box.size).toMutableList())
        }

        // Compute average color for each box
        val pal = IntArray(256)
        for (i in boxes.indices.take(256)) {
            val box = boxes[i]
            if (box.isEmpty()) continue
            var rSum = 0L; var gSum = 0L; var bSum = 0L
            for (c in box) { rSum += c[0]; gSum += c[1]; bSum += c[2] }
            val n = box.size
            val r = (rSum / n).toInt().coerceIn(0, 255)
            val g = (gSum / n).toInt().coerceIn(0, 255)
            val b = (bSum / n).toInt().coerceIn(0, 255)
            pal[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return pal
    }

    // ── Floyd-Steinberg dithering + nearest-color lookup ───────────

    /** Pre-built kd-tree-like cache for fast nearest-color lookup. */
    private var colorCache: HashMap<Int, Int>? = null

    private fun buildColorCache() {
        colorCache = HashMap(4096)
    }

    private fun nearestColor(r: Int, g: Int, b: Int): Int {
        // Quantize to 4-bit per channel for cache key (16x16x16 = 4096 buckets)
        val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
        val cache = colorCache ?: HashMap<Int, Int>(4096).also { colorCache = it }
        cache[key]?.let { return it }

        var bestIdx = 0
        var bestDist = Int.MAX_VALUE
        for (i in 0 until 256) {
            val c = palette[i]
            val dr = ((c shr 16) and 0xFF) - r
            val dg = ((c shr 8) and 0xFF) - g
            val db = (c and 0xFF) - b
            // Weighted distance (human eye is more sensitive to green)
            val dist = 2 * dr * dr + 4 * dg * dg + 3 * db * db
            if (dist < bestDist) { bestDist = dist; bestIdx = i }
        }
        cache[key] = bestIdx
        return bestIdx
    }

    private fun ditherAndQuantize(pixels: IntArray): ByteArray {
        buildColorCache()
        val indexed = ByteArray(width * height)

        // Error diffusion buffers (current row + next row)
        val errR = IntArray(width + 2)
        val errG = IntArray(width + 2)
        val errB = IntArray(width + 2)
        val nextErrR = IntArray(width + 2)
        val nextErrG = IntArray(width + 2)
        val nextErrB = IntArray(width + 2)

        for (y in 0 until height) {
            // Clear next row errors
            nextErrR.fill(0); nextErrG.fill(0); nextErrB.fill(0)

            for (x in 0 until width) {
                val px = pixels[y * width + x]
                val r = (((px shr 16) and 0xFF) + errR[x + 1]).coerceIn(0, 255)
                val g = (((px shr 8) and 0xFF) + errG[x + 1]).coerceIn(0, 255)
                val b = ((px and 0xFF) + errB[x + 1]).coerceIn(0, 255)

                val palIdx = nearestColor(r, g, b)
                indexed[y * width + x] = palIdx.toByte()

                val palColor = palette[palIdx]
                val er = r - ((palColor shr 16) and 0xFF)
                val eg = g - ((palColor shr 8) and 0xFF)
                val eb = b - (palColor and 0xFF)

                // Floyd-Steinberg error distribution: 7/16, 3/16, 5/16, 1/16
                errR[x + 2] += er * 7 / 16
                errG[x + 2] += eg * 7 / 16
                errB[x + 2] += eb * 7 / 16

                nextErrR[x] += er * 3 / 16
                nextErrG[x] += eg * 3 / 16
                nextErrB[x] += eb * 3 / 16

                nextErrR[x + 1] += er * 5 / 16
                nextErrG[x + 1] += eg * 5 / 16
                nextErrB[x + 1] += eb * 5 / 16

                nextErrR[x + 2] += er / 16
                nextErrG[x + 2] += eg / 16
                nextErrB[x + 2] += eb / 16
            }

            // Swap rows
            System.arraycopy(nextErrR, 0, errR, 0, width + 2)
            System.arraycopy(nextErrG, 0, errG, 0, width + 2)
            System.arraycopy(nextErrB, 0, errB, 0, width + 2)
        }
        return indexed
    }

    // ── GIF format writing ──────────────────────────────────────────

    private fun writeHeader() {
        out.write("GIF89a".toByteArray())
        writeShort(width)
        writeShort(height)
        out.write(0xF7) // GCT flag | color resolution 7 | size 7 (256 colors)
        out.write(0)    // background color index
        out.write(0)    // pixel aspect ratio
    }

    private fun writeGlobalColorTable() {
        for (i in 0 until 256) {
            val c = palette[i]
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }
    }

    private fun writeNetscapeExtension() {
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray())
        out.write(3); out.write(1)
        writeShort(0) // infinite loop
        out.write(0)
    }

    private fun writeGraphicControlExtension() {
        out.write(0x21); out.write(0xF9); out.write(4)
        out.write(0x04) // dispose: restore to background (prevents ghosting)
        writeShort(delayCs)
        out.write(0); out.write(0)
    }

    private fun writeImageDescriptor() {
        out.write(0x2C)
        writeShort(0); writeShort(0)
        writeShort(width); writeShort(height)
        out.write(0)
    }

    private fun writeLzwCompressed(pixels: ByteArray) {
        val minCodeSize = 8
        out.write(minCodeSize)
        val compressed = LzwEncoder(minCodeSize).encode(pixels)
        var offset = 0
        while (offset < compressed.size) {
            val blockSize = (compressed.size - offset).coerceAtMost(255)
            out.write(blockSize)
            out.write(compressed, offset, blockSize)
            offset += blockSize
        }
        out.write(0)
    }

    private fun writeShort(value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    // ── LZW Encoder ─────────────────────────────────────────────────

    private class LzwEncoder(private val minCodeSize: Int) {
        fun encode(pixels: ByteArray): ByteArray {
            val clearCode = 1 shl minCodeSize
            val eoiCode = clearCode + 1
            var codeSize = minCodeSize + 1
            var nextCode = eoiCode + 1

            val table = HashMap<Long, Int>(8192)
            val bitStream = BitOutputStream()

            fun initTable() { table.clear(); nextCode = eoiCode + 1; codeSize = minCodeSize + 1 }
            fun tableKey(prefix: Int, append: Int): Long = (prefix.toLong() shl 16) or append.toLong()

            initTable()
            bitStream.write(clearCode, codeSize)
            if (pixels.isEmpty()) { bitStream.write(eoiCode, codeSize); return bitStream.toByteArray() }

            var prefixCode = pixels[0].toInt() and 0xFF
            for (i in 1 until pixels.size) {
                val appendByte = pixels[i].toInt() and 0xFF
                val key = tableKey(prefixCode, appendByte)
                if (table.containsKey(key)) {
                    prefixCode = table[key]!!
                } else {
                    bitStream.write(prefixCode, codeSize)
                    if (nextCode < 4096) {
                        table[key] = nextCode++
                        if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
                    } else {
                        bitStream.write(clearCode, codeSize)
                        initTable()
                    }
                    prefixCode = appendByte
                }
            }
            bitStream.write(prefixCode, codeSize)
            bitStream.write(eoiCode, codeSize)
            return bitStream.toByteArray()
        }
    }

    private class BitOutputStream {
        private val bytes = mutableListOf<Byte>()
        private var currentByte = 0
        private var bitPos = 0

        fun write(code: Int, numBits: Int) {
            var remaining = numBits; var value = code
            while (remaining > 0) {
                currentByte = currentByte or ((value and 1) shl bitPos)
                bitPos++; value = value shr 1; remaining--
                if (bitPos == 8) { bytes.add(currentByte.toByte()); currentByte = 0; bitPos = 0 }
            }
        }

        fun toByteArray(): ByteArray {
            if (bitPos > 0) bytes.add(currentByte.toByte())
            return bytes.toByteArray()
        }
    }
}
