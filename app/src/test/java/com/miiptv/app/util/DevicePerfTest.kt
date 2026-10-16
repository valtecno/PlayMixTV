package com.miiptv.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePerfTest {
    private val GB = 1024L * 1024L * 1024L

    @Test
    fun `un equipo de 1 GB es gama baja`() {
        assertTrue(DevicePerf.es(false, 1 * GB, 128, 4))
    }

    @Test
    fun `un equipo de 4 GB con 8 nucleos no lo es`() {
        assertFalse(DevicePerf.es(false, 4 * GB, 256, 8))
    }

    @Test
    fun `marcado como low ram o con 2 nucleos es gama baja`() {
        assertTrue(DevicePerf.es(true, 4 * GB, 256, 8))
        assertTrue(DevicePerf.es(false, 4 * GB, 256, 2))
    }

    @Test
    fun `la memoria de imagenes se acota segun el equipo`() {
        val heap = 512L * 1024 * 1024
        assertEquals(24 * 1024 * 1024, DevicePerf.memoriaImagenes(heap, true))
        assertEquals(96 * 1024 * 1024, DevicePerf.memoriaImagenes(heap, false))
        // con poco heap nunca baja de 8 MB
        assertEquals(8 * 1024 * 1024, DevicePerf.memoriaImagenes(32L * 1024 * 1024, true))
    }
}
