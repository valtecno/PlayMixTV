package com.miiptv.app.util

import android.content.Context
import com.miiptv.app.api.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Caché en memoria del catálogo completo del servidor (canales, películas y series).
 *
 * Existe por dos motivos:
 *  1. El buscador necesita todo el catálogo cargado para filtrar al instante. Antes lo
 *     descargaba de cero cada vez que abrías la lupa (varios segundos, y si escribías
 *     antes de que terminara no encontraba nada).
 *  2. El carrusel de novedades necesita los mismos datos, ordenados por fecha de alta.
 *
 * Se carga una sola vez y se reutiliza durante toda la sesión de la app.
 */
object Catalog {

    /** Cuánto tiempo se considera fresco el catálogo antes de volver a pedirlo (30 min). */
    private const val TTL_MS = 30 * 60 * 1000L

    val live = mutableListOf<ContentItem>()
    val movies = mutableListOf<ContentItem>()
    val series = mutableListOf<ContentItem>()

    private var loadedAt = 0L
    private var loading = false
    private val calls = mutableListOf<Call<*>>()

    /** Callbacks a los que avisar cuando llega cada bloque de datos. */
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    val isEmpty: Boolean get() = live.isEmpty() && movies.isEmpty() && series.isEmpty()

    private val isFresh: Boolean
        get() = !isEmpty && (System.currentTimeMillis() - loadedAt) < TTL_MS

    fun all(): List<ContentItem> = live + movies + series

    /**
     * Carga el catálogo si hace falta.
     *
     * @param onUpdate se llama cada vez que llega un bloque (en vivo / películas / series)
     *                 con `true` mientras siga cargando algo, y `false` al terminar todo.
     *                 Esto permite que el buscador re-filtre solo a medida que llegan datos.
     */
    fun ensureLoaded(context: Context, force: Boolean = false, onUpdate: (stillLoading: Boolean) -> Unit) {
        if (isFresh && !force) {
            onUpdate(false)
            return
        }

        listeners.add(onUpdate)
        if (loading) return

        loading = true
        live.clear(); movies.clear(); series.clear()

        val user = Session.username(context)
        val pass = Session.password(context)
        val api = Session.api(context)
        var pending = 3

        fun blockDone() {
            pending--
            val stillLoading = pending > 0
            if (!stillLoading) {
                loading = false
                loadedAt = System.currentTimeMillis()
                calls.clear()
            }
            // copia defensiva: un listener puede quitarse a sí mismo durante el aviso
            listeners.toList().forEach { it(stillLoading) }
            if (!stillLoading) listeners.clear()
        }

        val liveCall = api.getLiveStreams(user, pass)
        calls.add(liveCall)
        liveCall.enqueue(object : Callback<List<LiveStream>> {
            override fun onResponse(call: Call<List<LiveStream>>, response: Response<List<LiveStream>>) {
                live.addAll(response.body().orEmpty().map { it.toContentItem() }.filter { it.name.isNotBlank() })
                blockDone()
            }
            override fun onFailure(call: Call<List<LiveStream>>, t: Throwable) { blockDone() }
        })

        val vodCall = api.getVodStreams(user, pass)
        calls.add(vodCall)
        vodCall.enqueue(object : Callback<List<VodStream>> {
            override fun onResponse(call: Call<List<VodStream>>, response: Response<List<VodStream>>) {
                movies.addAll(response.body().orEmpty().map { it.toContentItem() }.filter { it.name.isNotBlank() })
                blockDone()
            }
            override fun onFailure(call: Call<List<VodStream>>, t: Throwable) { blockDone() }
        })

        val seriesCall = api.getSeries(user, pass)
        calls.add(seriesCall)
        seriesCall.enqueue(object : Callback<List<SeriesItem>> {
            override fun onResponse(call: Call<List<SeriesItem>>, response: Response<List<SeriesItem>>) {
                series.addAll(response.body().orEmpty().map { it.toContentItem() }.filter { it.name.isNotBlank() })
                blockDone()
            }
            override fun onFailure(call: Call<List<SeriesItem>>, t: Throwable) { blockDone() }
        })
    }

    /** Deja de avisar a una pantalla que se está cerrando (evita tocar vistas ya destruidas). */
    fun removeListener(onUpdate: (Boolean) -> Unit) {
        listeners.remove(onUpdate)
    }

    /**
     * Novedades: lo último agregado al servidor (películas y series), más reciente primero.
     * Descarta lo que esté en una categoría bloqueada por control parental.
     */
    fun newest(context: Context, limit: Int = 20): List<ContentItem> {
        val visible = (movies + series)
            .filter { !Parental.isCategoryLocked(context, it.categoryId) }

        // Preferimos la fecha real de alta. Algunos paneles Xtream no la devuelven,
        // así que en ese caso caemos a ordenar por ID descendente (lo más nuevo
        // suele tener el ID más alto).
        val conFecha = visible.filter { it.added > 0 }
        return if (conFecha.isNotEmpty()) {
            conFecha.sortedByDescending { it.added }.take(limit)
        } else {
            visible.sortedByDescending { it.id }.take(limit)
        }
    }

    /** Vacía la caché (al cerrar sesión o al forzar una actualización manual). */
    fun clear() {
        calls.forEach { runCatching { it.cancel() } }
        calls.clear()
        listeners.clear()
        live.clear(); movies.clear(); series.clear()
        loadedAt = 0L
        loading = false
    }
}
