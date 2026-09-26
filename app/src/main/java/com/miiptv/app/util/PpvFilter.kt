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

    /**
     * Compiladas una sola vez. Antes `normalize` armaba la expresión regular
     * de nuevo en CADA llamada, y se llama miles de veces al repartir el
     * catálogo en Deportes - PPV (una o dos por canal).
     */
    private val DIACRITICOS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NO_ALFANUMERICO = Regex("[^a-z0-9]+")

    /** Quita acentos y pasa a minúsculas, para comparar sin sorpresas. */
    fun normalize(text: String): String {
        val minusculas = text.lowercase()
        // Atajo: la gran mayoría de los nombres son ASCII puro, sin acentos
        // que sacar; ahí no hace falta pasar por Normalizer ni por la regex.
        if (minusculas.all { it.code < 128 }) return minusculas
        return DIACRITICOS.replace(Normalizer.normalize(minusculas, Normalizer.Form.NFD), "")
    }

    /**
     * Igual que [normalize], pero además cambia cualquier separador
     * ("|", "-", "_", "·", etc.) por un espacio simple y junta espacios
     * repetidos. Sirve para comparar frases completas contra nombres de
     * carpeta reales, que suelen venir con separadores raros, por ej.
     * "CINEMA | HD | HQ" → "cinema hd hq" (así sí calza con la frase esperada).
     * No se usa en isFootball, que depende de los espacios tal cual vienen.
     */
    fun normalizeLoose(text: String): String =
        NO_ALFANUMERICO.replace(normalize(text), " ").trim()

    /**
     * Un nombre (de canal o de categoría) preparado UNA vez para compararlo
     * contra muchas palabras clave: normalizado, partido en palabras (en un
     * conjunto, para buscar cada palabra clave de un solo golpe) y en una
     * versión "acolchada" con espacios en los bordes para buscar frases.
     *
     * Así se exige que la palabra clave aparezca completa y no como parte
     * de otra (el problema de "CANTINFLAS" por "nfl", "GILIGANT" por "liga",
     * "Transporter" por "sport"...), pero sin usar una expresión regular por
     * palabra clave: esa versión anterior hacía ~90 búsquedas con regex por
     * canal y, con miles de canales, era lo que volvía lenta la sección.
     */
    class Texto internal constructor(original: String) {
        internal val normalizado: String = normalize(original)
        internal val palabras: Set<String>
        internal val acolchado: String

        init {
            val lista = ArrayList<String>()
            val actual = StringBuilder()
            for (c in normalizado) {
                if (c in 'a'..'z' || c in '0'..'9') {
                    actual.append(c)
                } else if (actual.isNotEmpty()) {
                    lista.add(actual.toString())
                    actual.setLength(0)
                }
            }
            if (actual.isNotEmpty()) lista.add(actual.toString())
            palabras = lista.toHashSet()
            acolchado = lista.joinToString(" ", prefix = " ", postfix = " ")
        }
    }

    /** Prepara un nombre para las comparaciones de este archivo; null si está vacío. */
    fun preparar(texto: String?): Texto? = if (texto.isNullOrBlank()) null else Texto(texto)

    /**
     * Una palabra clave, lista para compararse. Tres formas según cómo sea:
     *  - Una sola palabra alfanumérica ("nfl", "liga"): se busca en el
     *    conjunto de palabras del nombre. Tiene que estar completa.
     *  - Varias palabras ("formula 1", "fox sports"): se busca la frase con
     *    un espacio a cada lado, así tampoco calza a medias.
     *  - Con símbolos en el borde ("+18", "box "): se busca tal cual, como
     *    siempre. El símbolo ya la vuelve rara de encontrar por accidente.
     */
    private class Palabra(clave: String) {
        private val normalizada = normalize(clave)
        private val frase = " $normalizada "
        private val tipo: Int = run {
            val limpia = normalizada.isNotEmpty() &&
                normalizada.first() != ' ' && normalizada.last() != ' ' &&
                normalizada.all { it in 'a'..'z' || it in '0'..'9' || it == ' ' }
            when {
                !limpia -> TAL_CUAL
                ' ' in normalizada -> FRASE
                else -> PALABRA
            }
        }

        fun apareceEn(t: Texto): Boolean = when (tipo) {
            PALABRA -> normalizada in t.palabras
            FRASE -> t.acolchado.contains(frase)
            else -> t.normalizado.contains(normalizada)
        }

        private companion object {
            const val PALABRA = 0
            const val FRASE = 1
            const val TAL_CUAL = 2
        }
    }

    /**
     * Versión de una lista de palabras clave lista para comparar, calculada
     * una sola vez: las listas de este archivo son constantes.
     */
    private fun List<String>.aPalabras(): List<Palabra> = map { Palabra(it) }

    private fun List<Palabra>.apareceAlgunaEn(t: Texto): Boolean = any { it.apareceEn(t) }

    private val futbolPalabras by lazy { futbol.aPalabras() }
    private val genericasPalabras by lazy { genericas.aPalabras() }
    private val otrosDeportesPalabras by lazy { otrosDeportes.aPalabras() }
    private val deportesSistemaLPalabras by lazy { deportesSistemaL.aPalabras() }
    private val deportesSistemaXLPalabras by lazy { deportesSistemaXL.aPalabras() }

    /** ¿Este nombre (de canal o de categoría) corresponde a un evento PPV? */
    fun esPpv(t: Texto?): Boolean = t != null && t.normalizado.contains("ppv")

    /** ¿Esta categoría entra en la sección PPV Fútbol? */
    fun isFootball(categoryName: String?): Boolean {
        val t = preparar(categoryName) ?: return false
        if (otrosDeportesPalabras.apareceAlgunaEn(t)) return false
        if (futbolPalabras.apareceAlgunaEn(t)) return true
        return genericasPalabras.apareceAlgunaEn(t)
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
    fun isSportsChannel(name: String?, serverId: String?): Boolean =
        esDeporte(preparar(name), serverId)

    /** Igual que [isSportsChannel], para un nombre ya preparado con [preparar]. */
    fun esDeporte(t: Texto?, serverId: String?): Boolean {
        if (t == null) return false
        val lista = if (serverId == "xl") deportesSistemaXLPalabras else deportesSistemaLPalabras
        return lista.apareceAlgunaEn(t)
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
    fun sportTagFor(name: String?): SportTag? = deporteDe(preparar(name))

    /** Igual que [sportTagFor], para un nombre ya preparado con [preparar]. */
    fun deporteDe(t: Texto?): SportTag? {
        if (t == null) return null
        return TAGS.firstOrNull { tag -> tagPalabras.getValue(tag).apareceAlgunaEn(t) }
    }

    /** SportTag.values() crea un arreglo nuevo en cada llamada; este se arma una vez. */
    private val TAGS = SportTag.values().toList()
}
