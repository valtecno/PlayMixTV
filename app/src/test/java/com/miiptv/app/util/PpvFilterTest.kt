package com.miiptv.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La sección PPV se arma adivinando por el nombre de la carpeta, porque Xtream
 * no marca cuáles son de fútbol. Es la clase con más reglas escritas a mano del
 * proyecto: cada palabra que se agrega a una lista puede, sin querer, meter o
 * sacar categorías enteras.
 *
 * Estos tests fijan el comportamiento que se espera hoy, para que al agregar la
 * próxima palabra se vea enseguida si algo se rompió.
 */
class PpvFilterTest {

    // ---------------- Entra ----------------

    @Test
    fun `las menciones explicitas de futbol entran`() {
        assertTrue(PpvFilter.isFootball("PPV Futbol Sudamericano"))
        assertTrue(PpvFilter.isFootball("LaLiga"))
        assertTrue(PpvFilter.isFootball("Champions League"))
        assertTrue(PpvFilter.isFootball("Copa Libertadores"))
        assertTrue(PpvFilter.isFootball("Premier League"))
        assertTrue(PpvFilter.isFootball("Chile Primera"))
    }

    @Test
    fun `las carpetas genericas de eventos entran`() {
        // Regla 3: en la práctica casi siempre son de fútbol, y antes se perdían.
        assertTrue(PpvFilter.isFootball("PPV"))
        assertTrue(PpvFilter.isFootball("EVENTOS"))
        assertTrue(PpvFilter.isFootball("VIP Events"))
    }

    @Test
    fun `los acentos y las mayusculas no cambian el resultado`() {
        assertTrue(PpvFilter.isFootball("FÚTBOL"))
        assertTrue(PpvFilter.isFootball("fútbol"))
        assertTrue(PpvFilter.isFootball("Brasileirão"))
    }

    // ---------------- No entra ----------------

    @Test
    fun `otro deporte queda fuera aunque diga PPV`() {
        // Regla 1: el deporte gana sobre la palabra PPV.
        assertFalse(PpvFilter.isFootball("PPV NBA"))
        assertFalse(PpvFilter.isFootball("PPV UFC 300"))
        assertFalse(PpvFilter.isFootball("Eventos NFL"))
        assertFalse(PpvFilter.isFootball("PPV Boxeo"))
        assertFalse(PpvFilter.isFootball("Tenis ATP"))
    }

    @Test
    fun `el futbol americano no es futbol`() {
        // El caso más fácil de romper: contiene la palabra "futbol".
        assertFalse(PpvFilter.isFootball("Futbol Americano"))
        assertFalse(PpvFilter.isFootball("American Football"))
    }

    @Test
    fun `el contenido adulto nunca entra`() {
        assertFalse(PpvFilter.isFootball("PPV Adultos"))
        assertFalse(PpvFilter.isFootball("EVENTOS XXX"))
        assertFalse(PpvFilter.isFootball("PPV +18"))
    }

    @Test
    fun `una categoria sin relacion queda fuera`() {
        assertFalse(PpvFilter.isFootball("Documentales"))
        assertFalse(PpvFilter.isFootball("Cine Latino"))
        assertFalse(PpvFilter.isFootball("Noticias 24h"))
    }

    @Test
    fun `nombre vacio o nulo devuelve false`() {
        assertFalse(PpvFilter.isFootball(null))
        assertFalse(PpvFilter.isFootball(""))
        assertFalse(PpvFilter.isFootball("   "))
    }

    // ---------------- Normalización ----------------

    @Test
    fun `normalize saca acentos y baja a minusculas`() {
        assertEquals("futbol", PpvFilter.normalize("FÚTBOL"))
        assertEquals("brasileirao", PpvFilter.normalize("Brasileirão"))
        assertEquals("beisbol", PpvFilter.normalize("Béisbol"))
    }

    @Test
    fun `normalizeLoose convierte los separadores en espacios`() {
        // El caso que motivó la función: las carpetas reales vienen con barras.
        assertEquals("cinema hd hq", PpvFilter.normalizeLoose("CINEMA | HD | HQ"))
        assertEquals("vod estrenos", PpvFilter.normalizeLoose("VOD - ESTRENOS"))
        assertEquals("chile primera", PpvFilter.normalizeLoose("Chile_Primera"))
        assertEquals("chile primera 08 15", PpvFilter.normalizeLoose("Chile Primera 08/15"))
    }

    @Test
    fun `normalizeLoose no deja espacios en los bordes`() {
        assertEquals("ppv", PpvFilter.normalizeLoose("  ** PPV **  "))
    }
}
