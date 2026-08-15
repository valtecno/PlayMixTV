package com.miiptv.app.util

import java.text.Normalizer

/**
 * Detecta qué categorías del servidor son aptas para el **Perfil de niños**
 * (contenido infantil / +16 no permitido), reconociéndolas por el nombre —
 * igual que [PpvFilter] hace con el fútbol, porque Xtream no tiene un tipo
 * de categoría dedicado a esto.
 */
object KidsFilter {

    /** Edad máxima del público objetivo del perfil. */
    const val EDAD_MAXIMA = 10

    private val infantil = listOf(
        "infantil", "infantiles", "niños", "ninos", "kids", "kid", "child",
        "cartoon", "cartoons", "dibujos", "animacion", "animación",
        "disney junior", "disney jr", "discovery kids", "nick jr", "nickelodeon",
        "boomerang", "tooncast", "baby tv", "babytv", "jetix", "toons",
        "junior", "infancia", "preescolar", "pakapaka", "cartoonito", "clan tve",
        "peppa", "paw patrol", "bluey", "pocoyo", "pocoyó", "plaza sesamo", "plaza sésamo"
    )

    /**
     * Palabras que descartan la categoría aunque contenga alguna de las anteriores.
     *
     * El perfil apunta a chicos de hasta [EDAD_MAXIMA] años, así que además del
     * contenido adulto se descarta todo lo etiquetado para 12 años o más, y las
     * categorías "familiares"/juveniles, que suelen mezclar títulos por encima
     * de esa edad.
     */
    private val exclusiones = listOf(
        // Contenido adulto o violento
        "terror", "horror", "adult", "xxx", "+18", "18+", "gore", "erotic",
        "violencia", "sangriento", "thriller", "suspenso", "crimen",
        // Clasificaciones por encima de los 10 años
        "+11", "11+", "+12", "12+", "+13", "13+", "+14", "14+", "+15", "15+",
        "+16", "16+", "+17", "17+", "pg-13", "pg13", "rated r",
        // Franjas juveniles y de adultos jóvenes
        "teen", "teens", "adolescente", "adolescentes", "juvenil", "young adult",
        "familiar", "familia", "anime", "novela", "novelas", "reality",
        "wwe", "lucha", "ufc", "boxeo"
    )

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

    /** ¿Esta categoría es apta para chicos de hasta [EDAD_MAXIMA] años? */
    fun isKidsCategory(categoryName: String?): Boolean {
        if (categoryName.isNullOrBlank()) return false
        val name = normalize(categoryName)
        if (exclusiones.any { name.contains(normalize(it)) }) return false
        return infantil.any { name.contains(normalize(it)) }
    }
}
