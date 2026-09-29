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
    private const val MAX = 40

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
            type = type, containerExtension = containerExtension
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

    /**
     * Combina lo que bajó de la nube (ver DataSync) con lo que ya hay en este
     * equipo: por cada película o serie se queda con la entrada más nueva
     * ([Entry.updatedAt]), sin importar de qué lado vino.
     */
    fun mergeFromRemote(context: Context, remoto: List<Entry>): List<Entry> {
        fun clave(e: Entry) = "${e.type}:${e.id}"
        val combinado = LinkedHashMap<String, Entry>()
        leer(context).forEach { combinado[clave(it)] = it }
        remoto.forEach { r ->
            val actual = combinado[clave(r)]
            if (actual == null || r.updatedAt > actual.updatedAt) combinado[clave(r)] = r
        }
        val lista = combinado.values.toList()
        escribir(context, lista)
        return lista
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
        escribir(context, sin(leer(context), type, id))
    }

    /** Guarda dónde quedó una película; la saca de la lista si se terminó. */
    fun saveMovie(context: Context, movie: ContentItem, posMs: Long, durMs: Long) {
        val resto = sin(leer(context), ContentType.MOVIE, movie.id)
        if (posMs < MINIMO_MS || terminado(posMs, durMs)) {
            escribir(context, resto)
            return
        }
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
        val resto = sin(leer(context), ContentType.SERIES, serie.id)
        val ahora = System.currentTimeMillis()
        val base = Entry(
            type = ContentType.SERIES, id = serie.id, name = serie.name, icon = serie.icon,
            categoryId = serie.categoryId, posMs = posMs, durMs = durMs, updatedAt = ahora,
            episodeId = episodeId, episodeTitle = episodeTitle
        )
        when {
            terminado(posMs, durMs) && siguienteId != null ->
                escribir(context, resto + base.copy(
                    posMs = 0L, durMs = 0L, episodeId = siguienteId, episodeTitle = siguienteTitulo
                ))
            terminado(posMs, durMs) -> escribir(context, resto)
            // Recién empezado: si ya había una entrada de esta serie se deja
            // como estaba (no se pierde el avance por abrir otro capítulo un
            // segundo); si no había, tampoco vale la pena crearla.
            posMs < MINIMO_MS -> Unit
            else -> escribir(context, resto + base)
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
