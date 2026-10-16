package com.miiptv.app.util

import com.miiptv.app.api.ContentItem
import com.miiptv.app.api.ContentType

/**
 * "Recomendado para ti": sugiere películas y series que el perfil todavía no
 * vio, de las categorías que más ve. Pura (sin Android) para poder probarla.
 *
 * Cómo puntúa: cada ítem visto suma a su categoría (del mismo tipo), y suma
 * más cuanto más reciente es en el historial (el primero de la lista es el
 * último que se vio). Un candidato vale lo de su categoría más un pequeño
 * extra por ser novedad en el servidor (campo `added`), que solo desempata.
 */
object Recommender {

    /** Con menos vistos que esto no hay gusto que inferir: no se recomienda nada. */
    const val MIN_VISTOS = 3

    fun recomendar(
        vistos: List<ContentItem>,
        catalogo: List<ContentItem>,
        excluir: Set<String>,
        limite: Int = 40
    ): List<ContentItem> {
        val base = vistos.filter { it.type == ContentType.MOVIE || it.type == ContentType.SERIES }
        if (base.size < MIN_VISTOS) return emptyList()

        val n = base.size.toDouble()
        val puntosCategoria = HashMap<String, Double>()
        base.forEachIndexed { i, item ->
            val cat = item.categoryId ?: return@forEachIndexed
            val clave = "${item.type}:$cat"
            puntosCategoria[clave] = (puntosCategoria[clave] ?: 0.0) + 1.0 + (n - i) / n
        }
        if (puntosCategoria.isEmpty()) return emptyList()

        val candidatos = catalogo.filter {
            (it.type == ContentType.MOVIE || it.type == ContentType.SERIES) &&
                it.name.isNotBlank() && !it.icon.isNullOrBlank() &&
                clave(it) !in excluir &&
                puntosCategoria.containsKey("${it.type}:${it.categoryId}")
        }
        if (candidatos.isEmpty()) return emptyList()

        val masNuevo = candidatos.maxOf { it.added }.coerceAtLeast(1L).toDouble()
        return candidatos
            .sortedByDescending {
                (puntosCategoria["${it.type}:${it.categoryId}"] ?: 0.0) + it.added / masNuevo
            }
            .take(limite)
    }

    /** Clave "TIPO:ID" para armar el conjunto de exclusión. */
    fun clave(item: ContentItem) = "${item.type}:${item.id}"
}
