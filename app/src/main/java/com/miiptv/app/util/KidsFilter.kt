package com.miiptv.app.util

import java.text.Normalizer

/**
 * Detecta qué categorías del servidor son aptas para el **Perfil de niños**
 * (contenido infantil / +16 no permitido), reconociéndolas por el nombre —
 * igual que [PpvFilter] hace con el fútbol, porque Xtream no tiene un tipo
 * de categoría dedicado a esto.
 *
 * El padre elige, cada vez que activa el perfil, un tramo de edad ([AgeTier]).
 * Hoy los dos tramos que quedan ([AgeTier.HASTA_5] y [AgeTier.DIEZ]) filtran
 * exactamente igual: la única diferencia es la etiqueta que ve el padre en el
 * selector. Existieron dos tramos más estrictos/permisivos ("Menores de 5
 * años" y "Hasta 12 años") que se sacaron:
 *  - "Menores de 5 años" solo dejaba pasar marcas de bebés/preescolar (Baby
 *    TV, Disney Junior, Pocoyó...), pero la carpeta "Infantiles" típica de un
 *    panel Xtream no menciona ninguna de esas marcas por nombre, así que ese
 *    tramo devolvía Canales, Películas y Series vacíos en la práctica.
 *  - "Hasta 12 años" sumaba cine familiar y contenido +11/+12; se sacó a
 *    pedido, dejando el perfil de niños en dos tramos nada más.
 */
object KidsFilter {

    /** Edad máxima del tramo por defecto ([AgeTier.DIEZ]). */
    const val EDAD_MAXIMA = 10

    /**
     * Tramos de edad que el padre puede elegir al activar el perfil.
     * [etiqueta] y [descripcion] son lo que ve en el selector.
     */
    enum class AgeTier(val etiqueta: String, val descripcion: String) {
        HASTA_5(
            "Hasta 5 años",
            "Dibujos y programas infantiles en general"
        ),
        DIEZ(
            "Hasta 10 años",
            "El filtro infantil de siempre, sin cambios"
        )
    }

    private val infantil = listOf(
        "infantil", "infantiles", "niños", "ninos", "kids", "kid", "child",
        "cartoon", "cartoons", "dibujos", "animacion", "animación",
        "disney junior", "disney jr", "discovery kids", "nick jr", "nickelodeon",
        "boomerang", "tooncast", "baby tv", "babytv", "jetix", "toons",
        "junior", "infancia", "preescolar", "pakapaka", "cartoonito", "clan tve",
        "peppa", "paw patrol", "bluey", "pocoyo", "pocoyó", "plaza sesamo", "plaza sésamo"
    )

    /**
     * Palabras que descartan la categoría aunque contenga alguna infantil:
     * además del contenido adulto, descarta todo lo etiquetado para 11 años o
     * más, y las categorías "familiares"/juveniles, que suelen mezclar
     * títulos por encima de esa edad.
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

    /** Quita acentos y pasa a minúsculas, para comparar sin sorpresas. */
    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

    /**
     * ¿Esta categoría es apta para el Perfil de niños? [tier] queda como
     * parámetro por compatibilidad con quien lo llama (MainActivity,
     * MultiScreenActivity) y por si en el futuro vuelve a haber tramos que
     * filtren distinto; hoy los dos tramos que existen se comportan igual.
     */
    fun isKidsCategory(categoryName: String?, tier: AgeTier = AgeTier.DIEZ): Boolean {
        if (categoryName.isNullOrBlank()) return false
        val name = normalize(categoryName)

        if (exclusiones.any { name.contains(normalize(it)) }) return false
        return infantil.any { name.contains(normalize(it)) }
    }
}
