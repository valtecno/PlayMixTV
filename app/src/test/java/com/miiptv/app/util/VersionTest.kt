package com.miiptv.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Si esta comparación falla, falla en silencio: o la app nunca avisa que hay
 * versión nueva, o avisa una y otra vez sobre la que el usuario ya instaló.
 * Ninguno de los dos casos tira un error visible, así que conviene fijarlos acá.
 */
class VersionTest {

    @Test
    fun `la v del tag no cuenta`() {
        assertEquals("1.4.2", Version.normalize("v1.4.2"))
        assertEquals("1.4.2", Version.normalize("V1.4.2"))
        assertEquals("1.4.2", Version.normalize("  v1.4.2  "))
        assertEquals("1.4.2", Version.normalize("1.4.2"))
    }

    @Test
    fun `1_10 es mayor que 1_9`() {
        // El bug clásico: comparando como texto, "1.10" < "1.9" y la
        // actualización nunca se ofrece.
        assertTrue(Version.compare("1.10", "1.9") > 0)
        assertTrue(Version.isNewer("1.10", "1.9"))
        assertFalse(Version.isNewer("1.9", "1.10"))
    }

    @Test
    fun `los tramos que faltan valen cero`() {
        assertEquals(0, Version.compare("1.2", "1.2.0"))
        assertEquals(0, Version.compare("1", "1.0.0"))
        assertTrue(Version.compare("1.2.1", "1.2") > 0)
    }

    @Test
    fun `la misma version no se ofrece como actualizacion`() {
        assertFalse(Version.isNewer("1.4.2", "1.4.2"))
        assertFalse(Version.isNewer("v1.4.2", "1.4.2"))
        assertFalse(Version.isNewer("1.4.2", "v1.4.2"))
    }

    @Test
    fun `los sufijos tipo rc no alteran los tramos numericos`() {
        assertEquals(0, Version.compare("1.2.3", "1.2.3-rc1".substringBefore('-')))
        assertTrue(Version.isNewer("1.2.4", "1.2.3-rc1"))
    }

    @Test
    fun `una version con basura no explota`() {
        // Un tag mal puesto en GitHub no debe cerrar la app.
        assertFalse(Version.isNewer("", "1.0"))
        assertTrue(Version.isNewer("2.0", "sin-numeros"))
        assertEquals(0, Version.compare("", ""))
    }

    @Test
    fun `salto de version mayor`() {
        assertTrue(Version.isNewer("2.0.0", "1.99.99"))
        assertFalse(Version.isNewer("1.99.99", "2.0.0"))
    }
}
