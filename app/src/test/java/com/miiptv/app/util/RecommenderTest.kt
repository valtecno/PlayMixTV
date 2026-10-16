package com.miiptv.app.util

import com.miiptv.app.api.ContentItem
import com.miiptv.app.api.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderTest {

    private fun peli(id: Int, cat: String, added: Long = 0L) = ContentItem(
        id = id, name = "Peli $id", icon = "http://x/$id.jpg", categoryId = cat,
        type = ContentType.MOVIE, added = added
    )

    @Test
    fun `con pocos vistos no recomienda nada`() {
        val vistos = listOf(peli(1, "a"), peli(2, "a"))
        assertTrue(Recommender.recomendar(vistos, listOf(peli(3, "a")), emptySet()).isEmpty())
    }

    @Test
    fun `recomienda de la categoria mas vista y excluye lo ya visto`() {
        val vistos = listOf(peli(1, "terror"), peli(2, "terror"), peli(3, "comedia"))
        val catalogo = listOf(
            peli(1, "terror"), peli(4, "terror"), peli(5, "comedia"), peli(6, "drama")
        )
        val excluir = vistos.map { Recommender.clave(it) }.toSet()
        val r = Recommender.recomendar(vistos, catalogo, excluir)
        assertEquals(listOf(4, 5), r.map { it.id })   // terror primero; drama no (nunca vista)
    }

    @Test
    fun `a igual categoria gana la novedad`() {
        val vistos = listOf(peli(1, "a"), peli(2, "a"), peli(3, "a"))
        val catalogo = listOf(peli(10, "a", added = 100), peli(11, "a", added = 500))
        val excluir = vistos.map { Recommender.clave(it) }.toSet()
        assertEquals(listOf(11, 10), Recommender.recomendar(vistos, catalogo, excluir).map { it.id })
    }
}
