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

    /**
     * Una palabra clave, lista para compararse contra un nombre ya
     * normalizado. Antes esto era `nombre.contains(normalize(palabra))`, un
     * simple "substring": funcionaba mal con palabras cortas, porque
     * cualquier nombre que las tuviera COMO PARTE de otra palabra también
     * calzaba. Así, "CANTINFLAS" entraba a Deportes por contener "nfl",
     * "GILIGANT" por contener "liga", "TRANSPORTER" por contener "sport" y
     * "CHAMPIONSHIP" (de un programa de repostería) por contener "champions".
     * Ninguno de esos canales tiene que ver con deportes.
     *
     * La solución es exigir que la palabra aparezca completa (con un borde
     * de palabra a cada lado), no como fragmento de una más larga. Eso solo
     * tiene sentido cuando la palabra clave empieza y termina en letra o
     * número: una como "+18" ya tiene un símbolo en el borde que la vuelve
     * rara de encontrar por accidente, así que esas se quedan con el
     * "substring" de siempre.
     */
    private class Palabra(clave: String) {
        private val normalizada = normalize(clave)
        private val regexPalabraCompleta: Regex? = run {
            val esAlfanumerica = normalizada.isNotEmpty() &&
                normalizada.first().isLetterOrDigit() && normalizada.last().isLetterOrDigit()
            if (esAlfanumerica) Regex("\\b" + Regex.escape(normalizada) + "\\b") else null
        }

        fun apareceEn(textoNormalizado: String): Boolean =
            regexPalabraCompleta?.containsMatchIn(textoNormalizado)
                ?: textoNormalizado.contains(normalizada)
    }

    /**
     * Versión de una lista de palabras clave lista para comparar, calculada
     * una sola vez. Las listas de este archivo son constantes: prepararlas
     * en cada llamada (compilar expresiones regulares, normalizar texto)
     * repite el mismo trabajo miles de veces cuando se revisan todos los
     * canales del panel de una sola vez, que es justo lo que hace la carpeta
     * "Canales" de Deportes - PPV. En un panel grande eso alcanza a colgar
     * la app (ANR).
     */
    private fun List<String>.aPalabras(): List<Palabra> = map { Palabra(it) }

    private fun List<Palabra>.apareceAlgunaEn(textoNormalizado: String): Boolean =
        any { it.apareceEn(textoNormalizado) }

    private val futbolPalabras by lazy { futbol.aPalabras() }
    private val genericasPalabras by lazy { genericas.aPalabras() }
    private val otrosDeportesPalabras by lazy { otrosDeportes.aPalabras() }
    private val deportesSistemaLPalabras by lazy { deportesSistemaL.aPalabras() }
    private val deportesSistemaXLPalabras by lazy { deportesSistemaXL.aPalabras() }

    /** ¿Esta categoría entra en la sección PPV Fútbol? */
    fun isFootball(categoryName: String?): Boolean {
        if (categoryName.isNullOrBlank()) return false
        val name = normalize(categoryName)

        if (otrosDeportesPalabras.apareceAlgunaEn(name)) return false
        if (futbolPalabras.apareceAlgunaEn(name)) return true
        return genericasPalabras.apareceAlgunaEn(name)
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
        val lista = if (serverId == "xl") deportesSistemaXLPalabras else deportesSistemaLPalabras
        return lista.apareceAlgunaEn(texto)
    }

    // ---------------- Filtro rápido por tipo de deporte ----------------

    /**
     * Etiquetas del filtro rápido que se muestra dentro de "Canales", para
     * ayudar a elegir cuando se sabe qué deporte se quiere ver pero no un
     * canal o evento puntual. No reemplaza el buscador (etPpvSearch): es una
     * forma más rápida de acotar por tipo, sin tener que escribir nada.
     */
    enum class SportTag(val etiqueta: String) {
        FUTBOL("Fútbol"),
        BALONCESTO("Básquet"),
        BEISBOL("Béisbol"),
        TENIS("Tenis"),
        BOXEO("Boxeo / UFC"),
        MOTOR("Fórmula 1 / Motor"),
        OTROS("Otros deportes")
    }

    private val tagKeywords: Map<SportTag, List<String>> = mapOf(
        SportTag.FUTBOL to listOf(
            "futbol", "football", "soccer", "liga", "champions", "uefa", "europa league",
            "mundial", "fifa", "copa", "libertadores", "sudamericana", "concacaf",
            "bundesliga", "serie a", "ligue 1", "premier", "laliga", "mls", "primera",
            "eredivisie", "eliminatorias", "supercopa"
        ),
        SportTag.BALONCESTO to listOf("nba", "basket", "baloncesto"),
        SportTag.BEISBOL to listOf("mlb", "beisbol", "béisbol", "baseball"),
        SportTag.TENIS to listOf("tenis", "tennis", "atp", "wta"),
        SportTag.BOXEO to listOf("boxeo", "boxing", "box ", "ufc", "mma"),
        SportTag.MOTOR to listOf("f1", "formula 1", "formula1", "nascar", "motogp", "moto gp"),
        SportTag.OTROS to listOf(
            "nfl", "nhl", "rugby", "hockey", "voley", "volley", "cricket",
            "atletismo", "ciclismo", "golf"
        )
    )

    private val tagPalabras by lazy { tagKeywords.mapValues { (_, claves) -> claves.aPalabras() } }

    /**
     * A qué deporte corresponde este nombre (canal o categoría), o null si
     * no calza con ninguna etiqueta del filtro rápido. Cuando un nombre
     * calza con más de una (poco común), gana la que aparece primero en
     * [SportTag], en el mismo orden en que se muestran los chips.
     */
    fun sportTagFor(name: String?): SportTag? {
        if (name.isNullOrBlank()) return null
        val texto = normalize(name)
        return SportTag.values().firstOrNull { tag -> tagPalabras.getValue(tag).apareceAlgunaEn(texto) }
    }
}
