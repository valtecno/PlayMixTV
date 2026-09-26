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
     * Versión ya normalizada de una lista de palabras clave, calculada una
     * sola vez. Las listas de este archivo son constantes: normalizar cada
     * palabra en cada llamada (como se hacía antes, `name.contains(normalize(it))`
     * dentro del `any`) repite el mismo trabajo miles de veces cuando se
     * revisan todos los canales del panel de una sola vez, que es justo lo
     * que hace la carpeta "Canales" de Deportes - PPV. En un panel grande eso
     * alcanza a colgar la app (ANR).
     */
    private fun List<String>.normalized(): List<String> = map { normalize(it) }

    private val futbolNorm by lazy { futbol.normalized() }
    private val genericasNorm by lazy { genericas.normalized() }
    private val otrosDeportesNorm by lazy { otrosDeportes.normalized() }
    private val deportesSistemaLNorm by lazy { deportesSistemaL.normalized() }
    private val deportesSistemaXLNorm by lazy { deportesSistemaXL.normalized() }

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

        if (otrosDeportesNorm.any { name.contains(it) }) return false
        if (futbolNorm.any { name.contains(it) }) return true
        return genericasNorm.any { name.contains(it) }
    }

    /**
     * Palabras que identifican un canal de deportes para la carpeta "Canales"
     * de Deportes - PPV. Cada servidor nombra sus carpetas distinto, así que
     * la lista se arma por servidor en vez de una sola genérica: en Sistema L
     * las carpetas dicen "Deportes", "NBA", "MLB", etc.; en Sistema XL dicen
     * "Futbol", "Chile", "Fox Sports", "Liga", "Primera", etc. Fuera de esta
     * lista, todo lo demás (cine, misceláneo, países, noticias...) queda
     * excluido de "Canales": esa carpeta es solo deportes, nunca mezclada.
     */
    private val deportesSistemaL = listOf(
        "deporte", "deportes", "sport", "sports",
        "nba", "mlb", "nfl", "nhl", "ufc", "mma", "boxeo", "boxing",
        "tenis", "tennis", "golf", "formula 1", "formula1", "f1", "nascar", "motogp", "moto gp",
        "beisbol", "béisbol", "baseball", "basket", "baloncesto", "voley", "volley", "rugby", "hockey",
        "futbol", "football", "soccer", "liga", "champions", "uefa", "mundial", "fifa",
        "atp", "wta", "cricket", "atletismo", "ciclismo"
    )

    private val deportesSistemaXL = listOf(
        "futbol", "football", "soccer", "chile", "deporte", "deportes", "sport", "sports",
        "fox sports", "fox", "liga", "primera", "tenis", "tennis", "formula 1", "formula1", "f1", "league",
        "nba", "mlb", "nfl", "nhl", "ufc", "mma", "boxeo", "boxing", "golf", "nascar", "motogp", "moto gp",
        "beisbol", "béisbol", "baseball", "basket", "baloncesto", "voley", "volley", "rugby", "hockey",
        "champions", "uefa", "mundial", "fifa", "atp", "wta", "cricket"
    )

    /**
     * ¿Este nombre (de canal o de su categoría) es de deportes? Se usa para
     * la carpeta "Canales" de Deportes - PPV: [serverId] es [Servers.Server.id]
     * ("l" o "xl"); cualquier otro valor (o null) cae en la lista de Sistema L.
     */
    fun isSportsChannel(name: String?, serverId: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val texto = normalize(name)
        val lista = if (serverId == "xl") deportesSistemaXLNorm else deportesSistemaLNorm
        return lista.any { texto.contains(it) }
    }
}
