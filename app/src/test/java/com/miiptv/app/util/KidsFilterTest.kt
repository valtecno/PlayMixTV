package com.miiptv.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * De todos los filtros por nombre, este es el que más caro sale equivocarse:
 * un falso positivo mete contenido no apto en el perfil de un chico.
 *
 * Por eso los tests insisten en el orden de las reglas: las exclusiones se
 * evalúan ANTES que las palabras infantiles, así "Terror Animado" queda fuera
 * aunque diga "animado".
 */
class KidsFilterTest {

    @Test
    fun `las categorias infantiles entran`() {
        assertTrue(KidsFilter.isKidsCategory("Infantil"))
        assertTrue(KidsFilter.isKidsCategory("KIDS"))
        assertTrue(KidsFilter.isKidsCategory("Dibujos Animados"))
        assertTrue(KidsFilter.isKidsCategory("Discovery Kids"))
        assertTrue(KidsFilter.isKidsCategory("Disney Junior"))
        assertTrue(KidsFilter.isKidsCategory("Cartoon Network"))
    }

    @Test
    fun `los acentos no cambian el resultado`() {
        assertTrue(KidsFilter.isKidsCategory("Niños"))
        assertTrue(KidsFilter.isKidsCategory("NIÑOS"))
        assertTrue(KidsFilter.isKidsCategory("Animación"))
        assertTrue(KidsFilter.isKidsCategory("Pocoyó"))
    }

    @Test
    fun `una exclusion gana sobre una palabra infantil`() {
        // Este es el punto entero de la clase: el orden de las reglas.
        assertFalse(KidsFilter.isKidsCategory("Terror Animado"))
        assertFalse(KidsFilter.isKidsCategory("Kids +12"))
        assertFalse(KidsFilter.isKidsCategory("Dibujos Adultos"))
        assertFalse(KidsFilter.isKidsCategory("Anime Infantil"))
    }

    @Test
    fun `las clasificaciones por encima de los diez anios quedan fuera`() {
        assertFalse(KidsFilter.isKidsCategory("Infantil +12"))
        assertFalse(KidsFilter.isKidsCategory("Cartoon 13+"))
        assertFalse(KidsFilter.isKidsCategory("Kids PG-13"))
        assertFalse(KidsFilter.isKidsCategory("Animacion +16"))
    }

    @Test
    fun `familiar y juvenil no son infantil`() {
        // Mezclan títulos por encima de la edad objetivo del perfil.
        assertFalse(KidsFilter.isKidsCategory("Cine Familiar"))
        assertFalse(KidsFilter.isKidsCategory("Kids y Familia"))
        assertFalse(KidsFilter.isKidsCategory("Teen"))
    }

    @Test
    fun `una categoria comun no entra`() {
        assertFalse(KidsFilter.isKidsCategory("Deportes"))
        assertFalse(KidsFilter.isKidsCategory("Noticias"))
        assertFalse(KidsFilter.isKidsCategory("Cine de Accion"))
    }

    @Test
    fun `nombre vacio o nulo devuelve false`() {
        assertFalse(KidsFilter.isKidsCategory(null))
        assertFalse(KidsFilter.isKidsCategory(""))
        assertFalse(KidsFilter.isKidsCategory("   "))
    }
}
