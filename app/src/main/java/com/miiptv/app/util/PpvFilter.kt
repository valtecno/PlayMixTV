package com.miiptv.app.util

import java.text.Normalizer

/**
 * Decide qué categorías del servidor entran en la sección **PPV Fútbol**.
 *
 * Los paneles Xtream no tienen un tipo "PPV" aparte: son categorías en vivo con
 * nombres libres, así que hay que reconocerlas por el nombre. La regla es:
 *
 *  1. Si el nombre menciona otro deporte, queda fuera (aunque diga PPV).
 *  2. Si menciona fútbol explícitamente, entra.
 *  3. Si es una carpeta genérica de PPV o eventos (sin deporte declarado),
 *     entra igual: en la práctica esas carpetas son casi siempre de fútbol,
 *     y antes se perdían por completo.
 */
object PpvFilter {

    /** Menciones explícitas de fútbol. */
    private val futbol = listOf(
        "futbol", "football", "soccer", "balompie",
        "liga", "laliga", "premier", "champions", "uefa", "europa league", "conference league",
        "conmebol", "libertadores", "sudamericana", "sudamericano", "recopa", "copa",
        "concacaf", "eliminatorias", "mundial", "fifa", "supercopa",
        "bundesliga", "serie a", "ligue 1", "eredivisie", "primeira", "calcio",
        "mls", "brasileirao", "brasileirão", "apertura", "clausura",
        "seleccion", "selecciones", "amistoso", "clasico", "derbi", "derby",
        "chile primera", "primera division", "primera b"
    )

    /** Carpetas genéricas de eventos: entran salvo que sean de otro deporte. */
    private val genericas = listOf("ppv", "evento", "eventos", "vip events", "sport events")

    /** Si aparece alguna de estas, la categoría queda descartada. */
    private val otrosDeportes = listOf(
        "futbol americano", "football americano", "american football",
        "nfl", "nba", "mlb", "nhl", "ufc", "mma", "boxeo", "boxing", "box ",
        "tenis", "tennis", "atp", "wta", "golf", "f1", "formula", "nascar", "motogp", "moto gp",
        "beisbol", "béisbol", "baseball", "basket", "baloncesto", "voley", "volley", "rugby",
        "hockey", "wwe", "aew", "lucha", "ciclismo", "atletismo", "natacion", "esports", "e-sports",
        "cricket", "dardos", "billar", "poker", "surf", "skate",
        // PPV de contenido adulto: nunca en esta sección
        "adultos", "adulto", "xxx", "+18", "18+"
    )

    /** Quita acentos y pasa a minúsculas, para comparar sin sorpresas. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

    /**
     * Igual que [normalize], pero además cambia cualquier separador
     * ("|", "-", "_", "·", etc.) por un espacio simple y junta espacios
     * repetidos. Sirve para comparar frases completas contra nombres de
     * carpeta reales, que suelen venir con separadores raros, por ej.
     * "CINEMA | HD | HQ" → "cinema hd hq" (así sí calza con la frase esperada).
     * No se usa en isFootball, que depende de los espacios tal cual vienen.
     */
    fun normalizeLoose(text: String): String =
        normalize(text)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /** ¿Esta categoría entra en la sección PPV Fútbol? */
    fun isFootball(categoryName: String?): Boolean {
        if (categoryName.isNullOrBlank()) return false
        val name = normalize(categoryName)

        if (otrosDeportes.any { name.contains(normalize(it)) }) return false
        if (futbol.any { name.contains(normalize(it)) }) return true
        return genericas.any { name.contains(normalize(it)) }
    }
}
