package com.miiptv.app.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Los tres tipos de contenido (canal, película, serie) se unifican en
 * ContentItem para poder compartir adaptador, favoritos e historial. Estos
 * tests fijan ese mapeo, sobre todo la conversión del campo "added": el panel
 * lo manda como texto y a veces vacío, y de ahí sale el orden de "Novedades".
 */
class ModelsTest {

    @Test
    fun `un canal en vivo se mapea completo`() {
        val item = LiveStream(
            num = 1,
            name = "ESPN HD",
            streamId = 555,
            streamIcon = "http://logo.png",
            categoryId = "12",
            added = "1700000000"
        ).toContentItem()

        assertEquals(555, item.id)
        assertEquals("ESPN HD", item.name)
        assertEquals("http://logo.png", item.icon)
        assertEquals("12", item.categoryId)
        assertEquals(ContentType.LIVE, item.type)
        assertEquals(1_700_000_000L, item.added)
    }

    @Test
    fun `una pelicula conserva la extension del contenedor`() {
        // Sin containerExtension la URL de reproducción se arma mal y el
        // servidor devuelve 404.
        val item = VodStream(
            num = 2,
            name = "Duna",
            streamId = 900,
            streamIcon = null,
            categoryId = "7",
            containerExtension = "mkv",
            added = "1700000001"
        ).toContentItem()

        assertEquals(ContentType.MOVIE, item.type)
        assertEquals("mkv", item.containerExtension)
    }

    @Test
    fun `una serie usa seriesId y last_modified`() {
        val item = SeriesItem(
            num = 3,
            name = "Breaking Bad",
            seriesId = 42,
            cover = "http://cover.jpg",
            categoryId = "9",
            lastModified = "1699999999"
        ).toContentItem()

        assertEquals(42, item.id)
        assertEquals(ContentType.SERIES, item.type)
        assertEquals("http://cover.jpg", item.icon)
        assertEquals(1_699_999_999L, item.added)
    }

    @Test
    fun `un added invalido queda en cero en vez de romper`() {
        // Casos reales de paneles Xtream: campo vacío, ausente o con texto.
        val casos = listOf(null, "", "   ", "no-es-un-numero", "1.5")
        casos.forEach { valor ->
            val item = LiveStream(1, "X", 1, null, "1", valor).toContentItem()
            assertEquals("added=$valor debería quedar en 0", 0L, item.added)
        }
    }

    @Test
    fun `added con espacios alrededor se lee igual`() {
        val item = LiveStream(1, "X", 1, null, "1", "  1700000000  ").toContentItem()
        assertEquals(1_700_000_000L, item.added)
    }

    @Test
    fun `un nombre nulo se convierte en cadena vacia y no en null`() {
        // El adaptador y el buscador asumen que name nunca es null.
        val item = LiveStream(1, null, 1, null, "1", null).toContentItem()
        assertEquals("", item.name)
    }
}
