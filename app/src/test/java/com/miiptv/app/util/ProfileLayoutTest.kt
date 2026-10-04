package com.miiptv.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selector de perfiles en móvil: 2 columnas centradas y círculos que se
 * encogen con la cantidad de perfiles, sin bajar nunca del mínimo para que
 * siempre se vean como círculos y no como rectángulos aplastados.
 */
class ProfileLayoutTest {

    // Celulares típicos (ancho x alto en píxeles, densidad).
    private val pantallas = listOf(
        Triple(720, 1560, 2.0f),
        Triple(1080, 2400, 2.75f),
        Triple(1440, 3200, 3.5f)
    )

    @Test
    fun `por defecto principal y ninos van arriba y el mas abajo centrado`() {
        // 3 ítems: principal, niños y "+"
        assertEquals(1, ProfileLayout.spanSize(0, 3))
        assertEquals(1, ProfileLayout.spanSize(1, 3))
        assertEquals(2, ProfileLayout.spanSize(2, 3))
    }

    @Test
    fun `con numero par de items todos ocupan una columna`() {
        for (i in 0 until 4) assertEquals(1, ProfileLayout.spanSize(i, 4))
    }

    @Test
    fun `con numero impar el ultimo ocupa la fila completa`() {
        assertEquals(1, ProfileLayout.spanSize(3, 5))
        assertEquals(2, ProfileLayout.spanSize(4, 5))
        assertEquals(2, ProfileLayout.spanSize(6, 7))
    }

    @Test
    fun `un solo item tambien queda centrado`() {
        assertEquals(2, ProfileLayout.spanSize(0, 1))
    }

    @Test
    fun `los circulos nunca bajan del minimo ni pasan del maximo`() {
        for ((w, h, d) in pantallas) {
            for (items in 1..8) {
                val px = ProfileLayout.avatarSizePx(items, w, h, d)
                assertTrue("items=$items px=$px", px >= (ProfileLayout.MIN_DP * d).toInt())
                assertTrue("items=$items px=$px", px <= (ProfileLayout.MAX_DP * d).toInt())
            }
        }
    }

    @Test
    fun `con mas perfiles los circulos nunca crecen`() {
        for ((w, h, d) in pantallas) {
            var anterior = Int.MAX_VALUE
            for (items in 1..8) {
                val px = ProfileLayout.avatarSizePx(items, w, h, d)
                assertTrue("items=$items px=$px anterior=$anterior", px <= anterior)
                anterior = px
            }
        }
    }

    @Test
    fun `dos circulos caben a lo ancho en un celular normal`() {
        for ((w, h, d) in pantallas) {
            for (items in 1..8) {
                val px = ProfileLayout.avatarSizePx(items, w, h, d)
                assertTrue("items=$items px=$px ancho=$w", 2 * px <= w)
            }
        }
    }

    @Test
    fun `con los 3 items por defecto los circulos tienen el tamano maximo`() {
        for ((w, h, d) in pantallas) {
            assertEquals((ProfileLayout.MAX_DP * d).toInt(),
                ProfileLayout.avatarSizePx(3, w, h, d))
        }
    }
}
