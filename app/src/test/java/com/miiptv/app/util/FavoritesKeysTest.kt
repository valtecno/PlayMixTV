package com.miiptv.app.util

import com.miiptv.app.api.ContentItem
import com.miiptv.app.api.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La clave de favoritos es el contrato con lo que YA está escrito en los
 * dispositivos de los usuarios: si cambia el formato, al actualizar la app
 * todos pierden sus favoritos sin ningún error visible. Falla en silencio, que
 * es la clase de fallo que estos tests existen para atrapar.
 *
 * Se prueba solo la parte pura (clave + índice). El resto de Favorites toca
 * SharedPreferences y necesitaría Robolectric, que este proyecto no usa.
 */
class FavoritesKeysTest {

    private fun item(
        id: Int,
        type: ContentType,
        name: String = "X",
        icon: String? = null
    ) = ContentItem(id = id, name = name, icon = icon, categoryId = "1", type = type)

    @Test
    fun `el formato de la clave es TIPO dos puntos ID`() {
        // Formato congelado: es lo que hay guardado en los dispositivos.
        assertEquals("LIVE:555", Favorites.uniqueKey(ContentType.LIVE, 555))
        assertEquals("MOVIE:900", Favorites.uniqueKey(ContentType.MOVIE, 900))
        assertEquals("SERIES:42", Favorites.uniqueKey(ContentType.SERIES, 42))
    }

    @Test
    fun `el mismo id en tipos distintos no colisiona`() {
        // Un canal 100 y una película 100 son cosas distintas; marcar uno no
        // puede marcar al otro.
        val canal = item(100, ContentType.LIVE)
        val pelicula = item(100, ContentType.MOVIE)
        assertFalse(Favorites.uniqueKey(canal) == Favorites.uniqueKey(pelicula))
    }

    @Test
    fun `un id negativo produce una clave valida`() {
        // Las radios sacan su id de String.hashCode(), que es negativo casi la
        // mitad de las veces. Ya hubo un fallo por tratar los negativos como
        // "dato ausente" (ver README 6.1.7); acá queda fijado que la clave los
        // acepta sin problema.
        val radio = item(-1_234_567, ContentType.LIVE)
        assertEquals("LIVE:-1234567", Favorites.uniqueKey(radio))
        assertTrue(Favorites.keysOf(listOf(radio)).contains("LIVE:-1234567"))
    }

    @Test
    fun `el indice contiene una clave por item`() {
        val items = listOf(
            item(1, ContentType.LIVE),
            item(2, ContentType.MOVIE),
            item(3, ContentType.SERIES)
        )
        val keys = Favorites.keysOf(items)

        assertEquals(3, keys.size)
        items.forEach { assertTrue(keys.contains(Favorites.uniqueKey(it))) }
    }

    @Test
    fun `el indice ignora los duplicados`() {
        // Dos entradas del mismo ítem (nombre o carátula cambiados en el panel)
        // siguen siendo un solo favorito.
        val keys = Favorites.keysOf(
            listOf(
                item(7, ContentType.MOVIE, name = "Duna"),
                item(7, ContentType.MOVIE, name = "Duna (2021)", icon = "http://otra.jpg")
            )
        )
        assertEquals(1, keys.size)
    }

    @Test
    fun `una lista vacia da un indice vacio`() {
        assertTrue(Favorites.keysOf(emptyList()).isEmpty())
    }
}
