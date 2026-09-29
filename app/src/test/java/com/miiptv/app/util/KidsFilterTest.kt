package com.miiptv.app.util

import com.miiptv.app.util.KidsFilter.AgeTier
import org.junit.Assert.assertEquals
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
 *
 * Los tests sin tramo explícito ejercitan el valor por defecto (AgeTier.DIEZ,
 * el comportamiento de siempre); los que sí lo pasan cubren los dos tramos
 * del selector de edad (Hasta 5 / Hasta 10), que hoy filtran exactamente
 * igual — la única diferencia es la etiqueta que ve el padre.
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

    // ---------------- Tramos de edad (selector) ----------------

    @Test
    fun `hasta 5 y diez anios se comportan igual que el filtro de siempre`() {
        assertTrue(KidsFilter.isKidsCategory("Cartoon Network", AgeTier.HASTA_5))
        assertTrue(KidsFilter.isKidsCategory("Cartoon Network", AgeTier.DIEZ))
        assertFalse(KidsFilter.isKidsCategory("Cine Familiar", AgeTier.HASTA_5))
        assertFalse(KidsFilter.isKidsCategory("Cine Familiar", AgeTier.DIEZ))
        assertFalse(KidsFilter.isKidsCategory("Infantil +12", AgeTier.HASTA_5))
        assertFalse(KidsFilter.isKidsCategory("Infantil +12", AgeTier.DIEZ))
    }

    @Test
    fun `solo quedan dos tramos`() {
        assertEquals(
            listOf("HASTA_5", "DIEZ"),
            AgeTier.values().map { it.name }
        )
    }

    @Test
    fun `sin tramo se usa el de siempre (diez anios)`() {
        assertTrue(KidsFilter.isKidsCategory("Infantil") == KidsFilter.isKidsCategory("Infantil", AgeTier.DIEZ))
        assertTrue(KidsFilter.isKidsCategory("Cine Familiar") == KidsFilter.isKidsCategory("Cine Familiar", AgeTier.DIEZ))
    }
}
