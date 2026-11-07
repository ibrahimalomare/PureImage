package com.pureimage.app.data

import kotlin.math.abs

/** Mild 3x3 median blend. Classical denoising, not AI or a watermark detector. */
object LocalCleanup {
    fun apply(input: IntArray, width: Int, height: Int, checkActive: () -> Unit = {}): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == input.size.toLong())
        val output = input.copyOf()
        val red = IntArray(9)
        val green = IntArray(9)
        val blue = IntArray(9)
        for (y in 0 until height) {
            checkActive()
            for (x in 0 until width) {
                val center = input[y * width + x]
                // Preserve partially transparent pixels and their hidden RGB exactly.
                if ((center ushr 24) != 255) continue
                var count = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val c = input[(y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)]
                    if ((c ushr 24) != 255) continue
                    red[count] = (c ushr 16) and 255
                    green[count] = (c ushr 8) and 255
                    blue[count] = c and 255
                    count++
                }
                red.sort(0, count); green.sort(0, count); blue.sort(0, count)
                fun blend(channel: Int, median: Int): Int =
                    if (abs(channel - median) <= 40) (channel * 3 + median + 2) / 4 else channel
                val r = blend((center ushr 16) and 255, red[count / 2])
                val g = blend((center ushr 8) and 255, green[count / 2])
                val b = blend(center and 255, blue[count / 2])
                output[y * width + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return output
    }
}
