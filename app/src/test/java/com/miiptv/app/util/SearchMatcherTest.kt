package com.miiptv.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatcherTest {

    private fun p(nombre: String, q: String) =
        SearchMatcher.puntaje(SearchMatcher.normalizar(nombre), SearchMatcher.tokens(q))

    @Test
    fun `ignora mayusculas y tildes`() {
        assertTrue(p("CÁMARA Oculta", "camara") >= 0)
        assertTrue(p("Camara oculta", "CÁMARA") >= 0)
    }

    @Test
    fun `varias palabras en cualquier orden`() {
        assertTrue(p("My Hero Academia", "academia hero") >= 0)
        assertEquals(-1, p("My Hero Academia", "hero naruto"))
    }

    @Test
    fun `lo que empieza por la busqueda puntua mejor`() {
        assertEquals(0, p("Avatar", "avat"))
        assertTrue(p("El ultimo Avatar", "avat") > 0)
    }

    @Test
    fun `busqueda vacia no coincide`() {
        assertEquals(-1, p("Avatar", "   "))
    }
}
