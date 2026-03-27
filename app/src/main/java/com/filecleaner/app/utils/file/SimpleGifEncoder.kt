package com.filecleaner.app.utils.file

import android.graphics.Bitmap
import java.io.OutputStream

/**
 * Minimal GIF89a animated encoder using only Android SDK APIs.
 *
 * Produces small, compatible animated GIFs with a fixed 256-color palette
 * derived from median-cut quantization of the first frame. Subsequent frames
 * reuse the same palette for consistency and speed.
 *
 * Limitations:
 * - Fixed 256-color global palette (no per-frame local palettes)
 * - No transparency support
 * - No LZW compression optimization (uses standard LZW)
 */
class SimpleGifEncoder(private val out: OutputStream) {

    private var width = 0
    private var height = 0
    private var delayCs = 10 // centiseconds between frames
    private var palette = IntArray(256)
    private var started = false

    /**
     * Initializes the GIF with dimensions and frame delay.
     * @param w Width in pixels
     * @param h Height in pixels
     * @param delayCentiseconds Delay between frames in 1/100ths of a second
     */
    fun start(w: Int, h: Int, delayCentiseconds: Int = 10) {
        width = w
        height = h
        delayCs = delayCentiseconds
        started = true
    }

    /**
     * Adds a frame to the GIF. First frame also builds the global color palette.
     * Bitmap is NOT recycled by this method — caller manages lifecycle.
     */
    fun addFrame(bitmap: Bitmap) {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        if (!headerWritten) {
            palette = buildPalette(pixels)
            writeHeader()
            writeGlobalColorTable()
            writeNetscapeExtension() // loop forever
            headerWritten = true
        }

        val indexed = quantizePixels(pixels)
        writeGraphicControlExtension()
        writeImageDescriptor()
        writeLzwCompressed(indexed)
    }

    /** Finishes the GIF stream. */
    fun finish() {
        if (headerWritten) {
            out.write(0x3B) // GIF trailer
            out.flush()
        }
    }

    // ── Internal state ──────────────────────────────────────────────

    private var headerWritten = false

    // ── Palette building (median-cut simplified to uniform sampling) ──

    private fun buildPalette(pixels: IntArray): IntArray {
        // Sample colors uniformly across a 6x6x6 RGB cube (216 colors)
        // plus 40 most-frequent colors from the actual image
        val pal = IntArray(256)
        var idx = 0

        // 6x6x6 uniform cube = 216 entries
        for (r in 0..5) {
            for (g in 0..5) {
                for (b in 0..5) {
                    if (idx < 216) {
                        pal[idx++] = (0xFF shl 24) or
                            ((r * 51) shl 16) or ((g * 51) shl 8) or (b * 51)
                    }
                }
            }
        }

        // Fill remaining 40 slots with most frequent image colors (sampled)
        val freq = HashMap<Int, Int>(256)
        val step = (pixels.size / 1000).coerceAtLeast(1)
        for (i in pixels.indices step step) {
            val c = pixels[i] and 0x00F8F8F8.toInt() // quantize to 5-bit
            freq[c] = (freq[c] ?: 0) + 1
        }
        val topColors = freq.entries.sortedByDescending { it.value }.take(40)
        for (entry in topColors) {
            if (idx < 256) pal[idx++] = entry.key or (0xFF shl 24)
        }

        return pal
    }

    private fun quantizePixels(pixels: IntArray): ByteArray {
        val indexed = ByteArray(pixels.size)
        for (i in pixels.indices) {
            indexed[i] = nearestColor(pixels[i]).toByte()
        }
        return indexed
    }

    private fun nearestColor(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF

        // Fast lookup: map to nearest 6x6x6 cube index
        val ri = ((r + 25) / 51).coerceIn(0, 5)
        val gi = ((g + 25) / 51).coerceIn(0, 5)
        val bi = ((b + 25) / 51).coerceIn(0, 5)
        return ri * 36 + gi * 6 + bi
    }

    // ── GIF format writing ──────────────────────────────────────────

    private fun writeHeader() {
        out.write("GIF89a".toByteArray())
        writeShort(width)
        writeShort(height)
        // Global color table: 256 colors (2^8), 8 bits color resolution
        out.write(0xF7) // GCT flag | color resolution (7) | size (7 = 256 colors)
        out.write(0)    // background color index
        out.write(0)    // pixel aspect ratio
    }

    private fun writeGlobalColorTable() {
        for (i in 0 until 256) {
            val c = palette[i]
            out.write((c shr 16) and 0xFF) // R
            out.write((c shr 8) and 0xFF)  // G
            out.write(c and 0xFF)          // B
        }
    }

    private fun writeNetscapeExtension() {
        out.write(0x21)  // Extension
        out.write(0xFF)  // Application extension
        out.write(11)    // Block size
        out.write("NETSCAPE2.0".toByteArray())
        out.write(3)     // Sub-block size
        out.write(1)     // Loop sub-block ID
        writeShort(0)    // Loop count (0 = infinite)
        out.write(0)     // Block terminator
    }

    private fun writeGraphicControlExtension() {
        out.write(0x21) // Extension
        out.write(0xF9) // Graphic control
        out.write(4)    // Block size
        out.write(0)    // Packed: no disposal, no transparency
        writeShort(delayCs) // Delay in centiseconds
        out.write(0)    // Transparent color index (unused)
        out.write(0)    // Block terminator
    }

    private fun writeImageDescriptor() {
        out.write(0x2C) // Image separator
        writeShort(0)   // Left
        writeShort(0)   // Top
        writeShort(width)
        writeShort(height)
        out.write(0)    // No local color table, not interlaced
    }

    private fun writeLzwCompressed(pixels: ByteArray) {
        val minCodeSize = 8
        out.write(minCodeSize)

        val lzw = LzwEncoder(minCodeSize)
        val compressed = lzw.encode(pixels)

        // Write in sub-blocks of max 255 bytes
        var offset = 0
        while (offset < compressed.size) {
            val blockSize = (compressed.size - offset).coerceAtMost(255)
            out.write(blockSize)
            out.write(compressed, offset, blockSize)
            offset += blockSize
        }
        out.write(0) // Block terminator
    }

    private fun writeShort(value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    // ── LZW Encoder ─────────────────────────────────────────────────

    /**
     * LZW encoder using code-prefix keying (standard GIF LZW).
     * Keys are (prefixCode, appendByte) pairs encoded as a single Long
     * to avoid object allocation in the hot loop.
     */
    private class LzwEncoder(private val minCodeSize: Int) {
        fun encode(pixels: ByteArray): ByteArray {
            val clearCode = 1 shl minCodeSize
            val eoiCode = clearCode + 1
            var codeSize = minCodeSize + 1
            var nextCode = eoiCode + 1
            val maxTableSize = 4096

            // Key = (prefixCode << 16) | appendByte — safe because codes < 4096
            val table = HashMap<Long, Int>(maxTableSize * 2)
            val bitStream = BitOutputStream()

            fun initTable() {
                table.clear()
                nextCode = eoiCode + 1
                codeSize = minCodeSize + 1
            }

            fun tableKey(prefixCode: Int, appendByte: Int): Long =
                (prefixCode.toLong() shl 16) or appendByte.toLong()

            initTable()
            bitStream.write(clearCode, codeSize)

            if (pixels.isEmpty()) {
                bitStream.write(eoiCode, codeSize)
                return bitStream.toByteArray()
            }

            var prefixCode = pixels[0].toInt() and 0xFF

            for (i in 1 until pixels.size) {
                val appendByte = pixels[i].toInt() and 0xFF
                val key = tableKey(prefixCode, appendByte)

                if (table.containsKey(key)) {
                    prefixCode = table[key]!!
                } else {
                    bitStream.write(prefixCode, codeSize)

                    if (nextCode < maxTableSize) {
                        table[key] = nextCode++
                        if (nextCode > (1 shl codeSize) && codeSize < 12) {
                            codeSize++
                        }
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
            var remaining = numBits
            var value = code
            while (remaining > 0) {
                currentByte = currentByte or ((value and 1) shl bitPos)
                bitPos++
                value = value shr 1
                remaining--
                if (bitPos == 8) {
                    bytes.add(currentByte.toByte())
                    currentByte = 0
                    bitPos = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            if (bitPos > 0) {
                bytes.add(currentByte.toByte())
            }
            return bytes.toByteArray()
        }
    }
}
