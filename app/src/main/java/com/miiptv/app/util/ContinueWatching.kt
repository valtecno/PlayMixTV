package com.miiptv.app.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.miiptv.app.api.ContentItem
import com.miiptv.app.api.ContentType

/**
 * "Continuar viendo": películas y series que se dejaron a medias.
 *
 * Una entrada por película o por serie (no por episodio): de una serie se
 * recuerda el ÚLTIMO episodio que se estaba viendo y en qué minuto quedó. Si
 * ese episodio se terminó, la entrada pasa al siguiente de la temporada
 * (desde el principio), así la serie sigue en la lista hasta terminarla.
 *
 * Igual que [History] y Favoritos, cada cuenta tiene su propio archivo: lo
 * que se ve en Sistema L no aparece en Sistema XL.
 *
 * La posición exacta de CADA episodio la sigue guardando [EpisodeProgress];
 * esto es solo la lista para retomar rápido desde Películas y Series.
 */
object ContinueWatching {

    private const val KEY = "entries"
    private const val KEY_TOMBSTONES = "borrados"
    private const val MAX = 40
    /** Una marca de borrado más vieja que esto ya no aporta nada: se descarta. */
    private const val TOMBSTONE_MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000

    /** Antes de esto no vale la pena guardar: se abrió y se cerró enseguida. */
    private const val MINIMO_MS = 60 * 1000L

    /** A menos de esto del final, se considera terminado. */
    private const val FINAL_MS = 3 * 60 * 1000L

    data class Entry(
        val type: ContentType,
        /** Id de la película, o de la SERIE (no del episodio). */
        val id: Int,
        val name: String,
        val icon: String?,
        val categoryId: String?,
        /** Extensión del archivo, solo películas. */
        val containerExtension: String? = null,
        val posMs: Long,
        val durMs: Long,
        val updatedAt: Long,
        // Solo series: el episodio que se estaba viendo
        val episodeId: String? = null,
        val episodeTitle: String? = null
    ) {
        fun toContentItem() = ContentItem(
            id = id, name = name, icon = icon, categoryId = categoryId,
            type = type, containerExtension = containerExtension,
            progress = if (durMs > 0) (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f) else null
        )
    }

    private val gson = Gson()

    private fun accountKey(context: Context): String {
        val server = com.miiptv.app.api.Session.server(context)
        val user = com.miiptv.app.api.Session.username(context)
        return "$server|$user".replace(Regex("[^A-Za-z0-9]"), "_").take(80)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("miiptv_continue_${accountKey(context)}", Context.MODE_PRIVATE)

    private fun leer(context: Context): List<Entry> {
        val json = prefs(context).getString(KEY, null) ?: return emptyList()
        val tipo = object : TypeToken<List<Entry>>() {}.type
        return runCatching { gson.fromJson<List<Entry>>(json, tipo) }.getOrNull()
            // Gson no respeta la nulabilidad de Kotlin: descartar entradas rotas
            ?.filter { it.type != null && it.name != null }
            .orEmpty()
    }

    private fun escribir(context: Context, lista: List<Entry>) {
        prefs(context).edit()
            .putString(KEY, gson.toJson(lista.sortedByDescending { it.updatedAt }.take(MAX)))
            .apply()
        DataSync.scheduleBackup(context)
    }

    // ---------------- Marcas de borrado (para que el sync propague sacar una entrada) ----------------

    /**
     * Cuándo se sacó de "Continuar viendo" por última vez cada "TIPO:ID" que
     * ya no está en la lista actual (se terminó de ver, o se sacó a mano). Se
     * sube junto con las entradas (ver DataSync.Payload.continuarBorrados)
     * para que un equipo que todavía la tenga a medias sepa que hay que
     * sacarla, en vez de que la combinación la mantenga viva para siempre.
     */
    fun tombstones(context: Context): Map<String, Long> {
        val json = prefs(context).getString(KEY_TOMBSTONES, null) ?: return emptyMap()
        val tipo = object : TypeToken<Map<String, Long>>() {}.type
        return runCatching { gson.fromJson<Map<String, Long>>(json, tipo) }.getOrNull().orEmpty()
    }

    private fun guardarTombstones(context: Context, mapa: Map<String, Long>) {
        val corte = System.currentTimeMillis() - TOMBSTONE_MAX_AGE_MS
        val podado = mapa.filterValues { it >= corte }
        prefs(context).edit().putString(KEY_TOMBSTONES, gson.toJson(podado)).apply()
    }

    private fun clave(type: ContentType, id: Int) = "$type:$id"

    private fun marcarBorrado(context: Context, type: ContentType, id: Int, cuando: Long) {
        guardarTombstones(context, tombstones(context) + (clave(type, id) to cuando))
    }

    private fun quitarMarcaBorrado(context: Context, type: ContentType, id: Int) {
        val key = clave(type, id)
        val actuales = tombstones(context)
        if (key in actuales) guardarTombstones(context, actuales - key)
    }

    /**
     * Combina lo que bajó de la nube (ver DataSync) con lo que ya hay en este
     * equipo, resolviendo cada "TIPO:ID" por separado: gana el evento más
     * nuevo entre "a medias desde [Entry.updatedAt]" (local o remoto) y "se
     * sacó en [remoteTombstones]" (local o remoto) -igual que ya hace
     * Favorites.mergeFromRemote, ver ahí el porqué.
     */
    fun mergeFromRemote(
        context: Context,
        remoto: List<Entry>,
        remoteTombstones: Map<String, Long> = emptyMap()
    ): List<Entry> {
        fun clave(e: Entry) = clave(e.type, e.id)

        val local = leer(context)
        val localTombstones = tombstones(context)

        val claves = HashSet<String>(local.size + remoto.size)
        local.forEach { claves += clave(it) }
        remoto.forEach { claves += clave(it) }
        claves += localTombstones.keys
        claves += remoteTombstones.keys

        val localPorClave = local.associateBy(::clave)
        val remotoPorClave = remoto.associateBy(::clave)

        val resultado = mutableListOf<Entry>()
        val tombstonesFinal = HashMap<String, Long>()

        for (key in claves) {
            val entryLocal = localPorClave[key]
            val entryRemoto = remotoPorClave[key]
            val borradoLocal = localTombstones[key]
            val borradoRemoto = remoteTombstones[key]

            val eventoLocal = when {
                entryLocal != null -> entryLocal.updatedAt to entryLocal
                borradoLocal != null -> borradoLocal to null
                else -> null
            }
            val eventoRemoto = when {
                entryRemoto != null -> entryRemoto.updatedAt to entryRemoto
                borradoRemoto != null -> borradoRemoto to null
                else -> null
            }

            val ganador = when {
                eventoLocal == null -> eventoRemoto
                eventoRemoto == null -> eventoLocal
                eventoRemoto.first > eventoLocal.first -> eventoRemoto
                else -> eventoLocal
            } ?: continue

            val (cuando, entry) = ganador
            if (entry != null) resultado += entry else tombstonesFinal[key] = cuando
        }

        escribir(context, resultado)
        guardarTombstones(context, tombstonesFinal)
        return resultado
    }

    private fun sin(lista: List<Entry>, type: ContentType, id: Int) =
        lista.filterNot { it.type == type && it.id == id }

    private fun terminado(posMs: Long, durMs: Long) =
        durMs > 0 && (durMs - posMs < FINAL_MS || posMs >= durMs * 0.95)

    /** Lo pendiente de un tipo (películas o series), lo más reciente primero. */
    fun list(context: Context, type: ContentType): List<Entry> =
        leer(context).filter { it.type == type }.sortedByDescending { it.updatedAt }

    fun get(context: Context, type: ContentType, id: Int): Entry? =
        leer(context).firstOrNull { it.type == type && it.id == id }

    fun remove(context: Context, type: ContentType, id: Int) {
        marcarBorrado(context, type, id, System.currentTimeMillis())
        escribir(context, sin(leer(context), type, id))
    }

    /** Guarda dónde quedó una película; la saca de la lista si se terminó. */
    fun saveMovie(context: Context, movie: ContentItem, posMs: Long, durMs: Long) {
        val lista = leer(context)
        val existia = lista.any { it.type == ContentType.MOVIE && it.id == movie.id }
        val resto = sin(lista, ContentType.MOVIE, movie.id)
        if (posMs < MINIMO_MS || terminado(posMs, durMs)) {
            // Solo vale la pena marcar el borrado (para que se propague a
            // otros equipos) si de verdad había algo que sacar: una
            // reproducción cortísima de una película que nunca estuvo en
            // "Continuar viendo" no debería poder borrar, vía sync, el
            // avance real que otro equipo sí tenga guardado.
            if (existia) marcarBorrado(context, ContentType.MOVIE, movie.id, System.currentTimeMillis())
            escribir(context, resto)
            return
        }
        quitarMarcaBorrado(context, ContentType.MOVIE, movie.id)
        escribir(context, resto + Entry(
            type = ContentType.MOVIE, id = movie.id, name = movie.name, icon = movie.icon,
            categoryId = movie.categoryId, containerExtension = movie.containerExtension,
            posMs = posMs, durMs = durMs, updatedAt = System.currentTimeMillis()
        ))
    }

    /**
     * Guarda en qué episodio (y minuto) quedó una serie.
     *
     * @param siguienteId / siguienteTitulo el episodio que viene en la
     *        temporada, si hay: si el actual se terminó, la entrada queda
     *        apuntando a ese, desde el principio. Sin siguiente y terminado,
     *        la serie sale de la lista.
     */
    fun saveEpisode(
        context: Context,
        serie: ContentItem,
        episodeId: String,
        episodeTitle: String,
        posMs: Long,
        durMs: Long,
        siguienteId: String?,
        siguienteTitulo: String?
    ) {
        val listaSerie = leer(context)
        val existia = listaSerie.any { it.type == ContentType.SERIES && it.id == serie.id }
        val resto = sin(listaSerie, ContentType.SERIES, serie.id)
        val ahora = System.currentTimeMillis()
        val base = Entry(
            type = ContentType.SERIES, id = serie.id, name = serie.name, icon = serie.icon,
            categoryId = serie.categoryId, posMs = posMs, durMs = durMs, updatedAt = ahora,
            episodeId = episodeId, episodeTitle = episodeTitle
        )
        when {
            terminado(posMs, durMs) && siguienteId != null -> {
                quitarMarcaBorrado(context, ContentType.SERIES, serie.id)
                escribir(context, resto + base.copy(
                    posMs = 0L, durMs = 0L, episodeId = siguienteId, episodeTitle = siguienteTitulo
                ))
            }
            terminado(posMs, durMs) -> {
                // Ver el comentario equivalente en saveMovie: sin entrada
                // previa, no hay nada que propagar como borrado.
                if (existia) marcarBorrado(context, ContentType.SERIES, serie.id, ahora)
                escribir(context, resto)
            }
            // Recién empezado: si ya había una entrada de esta serie se deja
            // como estaba (no se pierde el avance por abrir otro capítulo un
            // segundo); si no había, tampoco vale la pena crearla.
            posMs < MINIMO_MS -> Unit
            else -> {
                quitarMarcaBorrado(context, ContentType.SERIES, serie.id)
                escribir(context, resto + base)
            }
        }
    }

    /** "23:10" o "1:02:05", para avisar desde dónde se retoma. */
    fun formato(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val seg = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, seg) else "%d:%02d".format(m, seg)
    }
}
