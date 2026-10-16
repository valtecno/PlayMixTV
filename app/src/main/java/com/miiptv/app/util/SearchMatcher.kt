package com.miiptv.app.util

/**
 * Comparación del buscador: sin importar mayúsculas ni tildes ("camara" encuentra
 * "Cámara") y con varias palabras en cualquier orden ("hero academia" encuentra
 * "My Hero Academia"). Pura, sin Android, para poder probarla.
 */
object SearchMatcher {

    /** Palabras de la búsqueda ya normalizadas (minúsculas, sin tildes). */
    fun tokens(query: String): List<String> =
        PpvFilter.normalize(query).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Nombre listo para comparar. Se calcula UNA vez por ítem, no en cada tecla. */
    fun normalizar(nombre: String): String = PpvFilter.normalize(nombre)

    /**
     * -1 si el nombre no contiene todas las palabras; si no, la posición de la
     * primera (0 = empieza por ahí, que es lo que se muestra primero).
     */
    fun puntaje(nombreNormalizado: String, tokens: List<String>): Int {
        if (tokens.isEmpty()) return -1
        var primera = -1
        for ((i, t) in tokens.withIndex()) {
            val pos = nombreNormalizado.indexOf(t)
            if (pos < 0) return -1
            if (i == 0) primera = pos
        }
        return primera
    }
}
