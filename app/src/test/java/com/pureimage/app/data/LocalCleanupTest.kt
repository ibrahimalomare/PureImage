package com.pureimage.app.data

import org.junit.Assert.*
import org.junit.Test

class LocalCleanupTest {
    @Test fun uniformImageIsUnchanged() {
        val input = IntArray(25) { 0xff808080.toInt() }
        assertArrayEquals(input, LocalCleanup.apply(input, 5, 5))
    }
    @Test fun reducesSmallNoiseWithoutMutatingInput() {
        val input = IntArray(9) { 0xff808080.toInt() }
        input[4] = 0xff909090.toInt()
        val result = LocalCleanup.apply(input, 3, 3)
        assertEquals(0xff8c8c8c.toInt(), result[4])
        assertEquals(0xff909090.toInt(), input[4])
    }
    @Test fun preservesStrongEdgesAndTransparency() {
        val input = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt(), 0x40808080)
        assertArrayEquals(input, LocalCleanup.apply(input, 3, 1))
    }
    @Test fun supportsSinglePixel() {
        val input = intArrayOf(0xff556677.toInt())
        assertArrayEquals(input, LocalCleanup.apply(input, 1, 1))
    }
    @Test(expected = IllegalStateException::class) fun cancellationStopsFilter() {
        LocalCleanup.apply(IntArray(4), 2, 2) { error("cancelled") }
    }
}
