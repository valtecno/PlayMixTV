package com.miiptv.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.miiptv.app.api.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Caché en memoria del catálogo completo del servidor (canales, películas y series).
 *
 * Existe por dos motivos:
 *  1. El buscador necesita todo el catálogo cargado para filtrar al instante.
 *  2. El carrusel de novedades necesita los mismos datos, ordenados por fecha de alta.
 *
 * ---------------------------------------------------------------------------
 * POR QUÉ CAMBIÓ RESPECTO A LA VERSIÓN ANTERIOR
 *
 * La versión anterior lanzaba las tres descargas grandes AL MISMO TIEMPO. En el
 * Sistema L funcionaba; en el Sistema XL no, por tres razones que se sumaban:
 *
 *  - Los paneles Xtream limitan las conexiones simultáneas por cuenta. Tres
 *    peticiones pesadas a la vez hacen que el panel corte una o las tres.
 *  - Las tres respuestas quedaban en memoria a la vez y, encima, cada una se
 *    duplicaba al mapear a ContentItem. En un panel XL eso son cientos de MB:
 *    un decodificador Android TV se queda sin heap y salta OutOfMemoryError.
 *  - Cualquier fallo se tragaba en silencio (`onFailure { blockDone() }`), así
 *    que la pantalla quedaba vacía sin ningún mensaje que explicara por qué.
 *
 * Ahora cada bloque se descarga con su propio reintento y sin lista
 * intermedia (ver XtreamStream), así que ya no hace falta serializarlos del
 * todo para cuidar la memoria: el pico real está dentro de cada bloque, no
 * entre bloques. Por eso los tres van EN PARALELO, con una salvedad: en el
 * Sistema XL, canales (LIVE) es el bloque más grande y antes iba último en
 * la fila, así que la pantalla de inicio (que solo necesita películas y
 * series para el carrusel de novedades) terminaba esperándolo igual por el
 * orden. Ahora arranca junto con los demás desde el primer instante, y el
 * Inicio se pinta en cuanto llegan películas + series sin importar cuánto
 * tarde el bloque de canales.
 * ---------------------------------------------------------------------------
 */
object Catalog {

    /** Cuánto tiempo se considera fresco el catálogo antes de volver a pedirlo (30 min). */
    private const val TTL_MS = 30 * 60 * 1000L

    /** Reintentos por bloque antes de darlo por perdido. */
    private const val MAX_RETRIES = 1

    /** Espera antes de reintentar un bloque que falló. */
    private const val RETRY_DELAY_MS = 1500L

    val live = mutableListOf<ContentItem>()
    val movies = mutableListOf<ContentItem>()
    val series = mutableListOf<ContentItem>()

    private var loadedAt = 0L
    private var loading = false

    /** Cuántos bloques quedan por resolver (LIVE + MOVIES + SERIES). 0 = terminó todo. */
    private var pending = 0

    /** Llamadas en curso, para poder cancelarlas todas si hace falta (hardReset / logout). */
    private val current = mutableListOf<Call<*>>()

    /**
     * Servidor y usuario con los que se llenó esta caché. Sin esto, al saltar de
     * Sistema L a Sistema XL la app podía seguir mostrando el catálogo del panel
     * anterior en el inicio y en el buscador.
     */
    private var stampServer: String = ""
    private var stampUser: String = ""

    /** Motivo del último bloque que no se pudo traer. null = todo bien. */
    var lastError: String? = null
        private set

    private val ui = Handler(Looper.getMainLooper())

    /** "no-cache" cuando el usuario pidió actualizar a mano; null el resto del tiempo. */
    private var noCache: String? = null

    /** Callbacks a los que avisar cuando llega cada bloque de datos. */
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    private enum class Block { LIVE, MOVIES, SERIES }

    val isEmpty: Boolean get() = live.isEmpty() && movies.isEmpty() && series.isEmpty()

    /** true mientras queda algún bloque por descargar. Lo usa el Inicio para no
     *  anunciar "no hay contenido" cuando en realidad todavía está bajando. */
    val isLoading: Boolean get() = loading

    private fun isFresh(context: Context): Boolean =
        !isEmpty && sameServer(context) && (System.currentTimeMillis() - loadedAt) < TTL_MS

    private fun sameServer(context: Context): Boolean =
        stampServer == Session.server(context).trim().trimEnd('/') &&
            stampUser == Session.username(context)

    fun all(): List<ContentItem> = live + movies + series

    /**
     * Carga el catálogo si hace falta.
     *
     * @param onUpdate se llama cada vez que llega un bloque (en vivo / películas / series)
     *                 con `true` mientras siga cargando algo, y `false` al terminar todo.
     */
    fun ensureLoaded(context: Context, force: Boolean = false, onUpdate: (stillLoading: Boolean) -> Unit) {
        val ctx = context.applicationContext

        // Si cambió el servidor o la cuenta, lo que haya en memoria ya no sirve.
        if (!isEmpty && !sameServer(ctx)) hardReset()

        if (isFresh(ctx) && !force) {
            onUpdate(false)
            return
        }

        if (!listeners.contains(onUpdate)) listeners.add(onUpdate)
        if (loading) return

        loading = true
        lastError = null
        live.clear(); movies.clear(); series.clear()
        stampServer = Session.server(ctx).trim().trimEnd('/')
        stampUser = Session.username(ctx)

        // Con force se manda "no-cache" en la petición: va sí o sí al panel,
        // sin borrar la caché de disco (borrarla es E/S y bloquearía la pantalla).
        noCache = if (force) "no-cache" else null

        // Los tres bloques salen a la vez. El panel Xtream sí limita conexiones
        // simultáneas por cuenta, pero el límite típico (varias a la vez) alcanza
        // de sobra para tres pedidos; lo que había que evitar era tener varias
        // listas completas duplicadas en memoria al mismo tiempo, y eso ya no
        // ocurre porque XtreamStream construye el ContentItem final sin lista
        // intermedia. Así, canales (el bloque grande en XL) no espera a nadie.
        pending = Block.entries.size
        Block.entries.forEach { fetch(ctx, it, attempt = 0) }
    }

    // ---------------- Descarga en paralelo, con reintento por bloque ----------------

    private fun fetch(context: Context, block: Block, attempt: Int) {
        val user = Session.username(context)
        val pass = Session.password(context)
        val api = Session.api(context)
        // En un reintento se fuerza ir al panel: si la respuesta anterior fue
        // mala y OkHttp la guardó, reintentar contra la caché daría lo mismo.
        val noCache = if (attempt > 0) "no-cache" else this.noCache

        when (block) {
            Block.LIVE -> execute(context, block, attempt, live,
                api.getLiveCatalog(user, pass, cacheControl = noCache)) { it?.items.orEmpty() }
            Block.MOVIES -> execute(context, block, attempt, movies,
                api.getVodCatalog(user, pass, cacheControl = noCache)) { it?.items.orEmpty() }
            Block.SERIES -> execute(context, block, attempt, series,
                api.getSeriesCatalog(user, pass, cacheControl = noCache)) { it?.items.orEmpty() }
        }
    }

    private fun <R> execute(
        context: Context,
        block: Block,
        attempt: Int,
        target: MutableList<ContentItem>,
        call: Call<R>,
        extract: (R?) -> List<ContentItem>
    ) {
        current.add(call)
        call.enqueue(object : Callback<R> {
            override fun onResponse(c: Call<R>, r: Response<R>) {
                if (!r.isSuccessful) {
                    failed(context, block, attempt, "HTTP ${r.code()}")
                    return
                }
                val intento = runCatching { extract(r.body()) }
                val fallo = intento.exceptionOrNull()
                if (fallo != null) {
                    failed(context, block, attempt, fallo::class.java.simpleName)
                    return
                }

                val items = intento.getOrDefault(emptyList())
                // Un bloque completamente vacío casi nunca es real: un panel no
                // tiene cero películas. Es un fallo disfrazado (tope de conexiones,
                // respuesta cortada, caché envenenada), así que se reintenta antes
                // de darlo por bueno. Este era exactamente el caso de "0 películas".
                if (items.isEmpty()) {
                    failed(context, block, attempt, "respuesta vacía")
                    return
                }

                target.addAll(items)
                advance(context, block)
            }

            override fun onFailure(c: Call<R>, t: Throwable) {
                if (c.isCanceled) return
                val motivo = when (t) {
                    is XtreamStream.ShapeException -> t.token
                    else -> t::class.java.simpleName
                }
                failed(context, block, attempt, motivo)
            }
        })
    }

    private fun failed(context: Context, block: Block, attempt: Int, motivo: String) {
        if (attempt < MAX_RETRIES) {
            ui.postDelayed({ if (loading) fetch(context, block, attempt + 1) }, RETRY_DELAY_MS)
            return
        }
        lastError = describe(block, motivo)
        advance(context, block)
    }

    private fun describe(block: Block, motivo: String): String {
        val nombre = when (block) {
            Block.LIVE -> "canales"
            Block.MOVIES -> "películas"
            Block.SERIES -> "series"
        }
        return "$nombre: $motivo"
    }

    /** Se llama cuando un bloque terminó (con datos o con error definitivo). */
    private fun advance(context: Context, done: Block) {
        pending -= 1
        if (pending > 0) {
            broadcast(true)
        } else {
            loading = false
            current.clear()
            noCache = null
            loadedAt = System.currentTimeMillis()
            broadcast(false)
        }
    }

    private fun broadcast(stillLoading: Boolean) {
        // copia defensiva: un listener puede quitarse a sí mismo durante el aviso
        listeners.toList().forEach { runCatching { it(stillLoading) } }
        if (!stillLoading) listeners.clear()
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
        hardReset()
        listeners.clear()
    }

    private fun hardReset() {
        current.forEach { runCatching { it.cancel() } }
        current.clear()
        pending = 0
        live.clear(); movies.clear(); series.clear()
        loadedAt = 0L
        loading = false
        lastError = null
        stampServer = ""
        stampUser = ""
    }
}
