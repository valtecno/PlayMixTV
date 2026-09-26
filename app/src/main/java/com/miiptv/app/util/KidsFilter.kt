package com.miiptv.app.util

import java.text.Normalizer

/**
 * Detecta qué categorías del servidor son aptas para el **Perfil de niños**
 * (contenido infantil / +16 no permitido), reconociéndolas por el nombre —
 * igual que [PpvFilter] hace con el fútbol, porque Xtream no tiene un tipo
 * de categoría dedicado a esto.
 *
 * El padre elige, cada vez que activa el perfil, un tramo de edad ([AgeTier])
 * que ajusta qué tan estricto es el filtro. La base histórica (todo lo
 * "infantil" hasta 12+ excluido) sigue siendo [AgeTier.HASTA_5] y
 * [AgeTier.DIEZ] — ambas se comportan exactamente igual que antes de que
 * existiera el selector, así que una app vieja sin tramo guardado no cambia
 * de comportamiento.
 *
 * Hubo un cuarto tramo, "Menores de 5 años", que solo dejaba pasar marcas de
 * bebés/preescolar (Baby TV, Disney Junior, Pocoyó...). Se sacó: la carpeta
 * "Infantiles" típica de un panel Xtream no menciona ninguna de esas marcas
 * por nombre, así que ese tramo devolvía Canales, Películas y Series vacíos
 * en la práctica. [AgeTier.HASTA_5] (antes "Mayores de 5 años") es ahora el
 * tramo más chico, y sigue usando la lista general [infantil], que sí calza
 * con cómo vienen nombradas las carpetas reales.
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
        ),
        HASTA_12(
            "Hasta 12 años",
            "El más permisivo: suma cine familiar y contenido +11/+12"
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
     * Palabras que descartan la categoría aunque contenga alguna infantil.
     *
     * Esta es la lista base, la que usan [AgeTier.HASTA_5] y [AgeTier.DIEZ]:
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

    /**
     * Igual que [exclusiones], pero sin "+11/+12" ni "familiar/familia": es
     * la que usa [AgeTier.HASTA_12], el único tramo que sube el techo por
     * encima de los 10 años. Todo lo demás (adulto, violento, +13 en
     * adelante, juvenil) se sigue descartando igual.
     */
    private val exclusionesHasta12 = exclusiones - setOf("+11", "11+", "+12", "12+", "familiar", "familia")

    /**
     * Palabras que solo cuentan como infantiles en [AgeTier.HASTA_12]: no
     * alcanza con sacarlas de la exclusión, porque una categoría como "Cine
     * Familiar" no menciona ninguna palabra de [infantil]. Sin esto, sacarlas
     * de [exclusionesHasta12] no lograba que ese tramo sumara nada nuevo.
     */
    private val familiares = listOf("familiar", "familia", "family")

    /** Quita acentos y pasa a minúsculas, para comparar sin sorpresas. */
    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

    /**
     * ¿Esta categoría es apta para el tramo de edad elegido? Sin tramo
     * (llamadas viejas, o app actualizada sin que el usuario haya vuelto a
     * elegir uno) se usa [AgeTier.DIEZ], el comportamiento de siempre.
     */
    fun isKidsCategory(categoryName: String?, tier: AgeTier = AgeTier.DIEZ): Boolean {
        if (categoryName.isNullOrBlank()) return false
        val name = normalize(categoryName)

        val exclusionesDelTramo = if (tier == AgeTier.HASTA_12) exclusionesHasta12 else exclusiones
        if (exclusionesDelTramo.any { name.contains(normalize(it)) }) return false

        val inclusionDelTramo = if (tier == AgeTier.HASTA_12) infantil + familiares else infantil
        return inclusionDelTramo.any { name.contains(normalize(it)) }
    }
}
