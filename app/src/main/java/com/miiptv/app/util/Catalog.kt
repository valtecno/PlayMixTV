package com.miiptv.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.miiptv.app.api.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.concurrent.Executors

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

    /**
     * Cambia cada vez que cambia el contenido de [live] (se vació, llegó
     * nuevo, se cargó del disco). Deportes - PPV lo usa para saber si puede
     * reutilizar el reparto que ya calculó en vez de recorrer todo de nuevo.
     */
    var liveVersion: Int = 0
        private set

    /**
     * ¿Se puede usar lo que hay en memoria tal cual, en vez de pedírselo al
     * panel? Tiene que ser de esta cuenta, estar dentro del tiempo de
     * frescura y no estar a medio recargar (los bloques se vacían al empezar).
     */
    fun isFreshFor(context: Context): Boolean = !loading && isFresh(context.applicationContext)

    /** ¿Lo que hay en memoria es de la cuenta y el servidor conectados ahora? */
    fun isFor(context: Context): Boolean = !isEmpty && sameServer(context.applicationContext)

    /** Motivo del último bloque que no se pudo traer. null = todo bien. */
    var lastError: String? = null
        private set

    private val ui = Handler(Looper.getMainLooper())

    /** "no-cache" cuando el usuario pidió actualizar a mano; null el resto del tiempo. */
    private var noCache: String? = null

    /** Callbacks a los que avisar cuando llega cada bloque de datos. */
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    private enum class Block { LIVE, MOVIES, SERIES }

    // ---- Caché offline ----
    // Cuando el catálogo carga correctamente se guarda en disco.
    // Si la próxima vez no hay conexión, se muestra el guardado para que la app
    // no quede con la pantalla vacía sin explicación.
    //
    // ---------------------------------------------------------------------------
    // POR QUÉ SON ARCHIVOS Y NO SharedPreferences (y por qué se escribe en un
    // hilo aparte)
    //
    // Antes esto guardaba cada lista como un String gigante en SharedPreferences
    // (gson.toJson(lista) sin más). El Sistema XL tiene decenas de miles de
    // películas/series: gson.toJson() arma el JSON en un StringWriter interno y
    // al final llama a StringWriter.toString(), que copia TODO el buffer a un
    // String nuevo de un solo golpe -- para un catálogo grande eso es un pedido
    // de una sola vez de decenas de MB contiguos.
    //
    // En un equipo con harta RAM libre eso pasa desapercibido. En un Android TV
    // box con poca memoria (algunos genéricos limitan el heap de cada app a
    // apenas 256 MB, sin importar cuánta RAM tenga el equipo) esa sola
    // asignación alcanzaba para un OutOfMemoryError -- y como esto se llamaba
    // synchronamente desde el callback de Retrofit (que en Android corre en el
    // hilo principal), el crash tumbaba toda la app justo al terminar de cargar
    // el Inicio: exactamente el síntoma reportado ("se queda la pantalla en
    // negro... se cierra"), confirmado con el código de errores real que mandó
    // el usuario (CrashLogger).
    //
    // La solución real son dos cambios juntos:
    //   1. Escribir cada lista con la variante de Gson que recibe un Writer
    //      (`gson.toJson(lista, type, writer)`), que escribe directo al archivo
    //      de a pedazos, sin armar nunca un String intermedio con el JSON
    //      completo. Leer usa el mismo truco al revés (JsonReader sobre el
    //      archivo, no String + parseo).
    //   2. Que esto pase en un hilo aparte, no en el callback de Retrofit. El
    //      catálogo ya está en memoria (en las listas [live]/[movies]/[series])
    //      apenas termina la descarga; guardarlo en disco es solo para la
    //      próxima vez que se abra sin conexión, así que no hay apuro ni
    //      necesidad de bloquear nada.
    // ---------------------------------------------------------------------------
    private val gson = Gson()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private const val CACHE_PREFS = "miiptv_catalog_cache"
    private const val CACHE_KEY_SERVER = "server"

    private fun archivoCache(context: Context, bloque: String): File =
        File(context.filesDir, "catalog_cache_$bloque.json")

    private fun escribirBloque(context: Context, bloque: String, lista: List<ContentItem>, type: java.lang.reflect.Type) {
        val destino = archivoCache(context, bloque)
        // Se escribe primero a un ".tmp" y se renombra al final: si la app se
        // cierra a mitad de la escritura (batería, memoria, lo que sea), el
        // archivo bueno de la vez anterior queda intacto en vez de quedar con
        // JSON cortado a la mitad e ilegible la próxima vez.
        val tmp = File(destino.parentFile, destino.name + ".tmp")
        BufferedWriter(FileWriter(tmp)).use { writer -> gson.toJson(lista, type, writer) }
        tmp.renameTo(destino)
    }

    private fun leerBloque(context: Context, bloque: String, type: java.lang.reflect.Type): List<ContentItem>? {
        val archivo = archivoCache(context, bloque)
        if (!archivo.isFile) return null
        return try {
            BufferedReader(FileReader(archivo)).use { reader -> gson.fromJson(reader, type) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Se llama en segundo plano (ver [ioExecutor] en el llamador); nunca en el
     * hilo principal. [liveSnap]/[moviesSnap]/[seriesSnap] son copias tomadas
     * ANTES de mandar esto al otro hilo: [live]/[movies]/[series] son las
     * listas mutables de verdad, y una recarga (o un cierre de sesión) puede
     * vaciarlas o llenarlas de nuevo mientras este hilo todavía las está
     * recorriendo para escribirlas. Sin la copia, esa carrera terminaba en
     * una ConcurrentModificationException o un archivo con datos mezclados.
     */
    private fun saveToDisk(
        context: Context,
        liveSnap: List<ContentItem>,
        moviesSnap: List<ContentItem>,
        seriesSnap: List<ContentItem>
    ) {
        runCatching {
            val type = object : TypeToken<List<ContentItem>>() {}.type
            escribirBloque(context, "live", liveSnap, type)
            escribirBloque(context, "movies", moviesSnap, type)
            escribirBloque(context, "series", seriesSnap, type)
            context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit()
                .putString(CACHE_KEY_SERVER, "${Session.server(context)}|${Session.username(context)}")
                .apply()
        }
    }

    /**
     * Carga el catálogo guardado en disco y devuelve true si había algo.
     * Solo se usa cuando la descarga en línea falla por completo.
     *
     * SIEMPRE en el hilo que la llama (ver [loadFromDiskAsync] para la
     * versión que corre en segundo plano). Queda pública por compatibilidad,
     * pero MainActivity ya no la llama directo: parsear el catálogo entero
     * -en un panel grande, decenas de miles de ítems- desde el hilo
     * principal significa construir todos esos objetos ahí mismo, justo al
     * abrir la app. En un Android TV box con poca memoria eso es exactamente
     * el tipo de ráfaga que puede hacer que el sistema mate el proceso por
     * memoria ANTES de que salte ninguna excepción atrapable -por eso ese
     * cierre nunca dejaba nada en CrashLogger, a diferencia del
     * OutOfMemoryError original (ese sí era una excepción de Java real,
     * capturable; esto es distinto: el sistema operativo corta el proceso
     * desde afuera).
     */
    fun loadFromDisk(context: Context): Boolean {
        val leido = leerDeDiscoPuro(context) ?: return false
        aplicarLeidoDeDisco(context, leido)
        return true
    }

    /** Lo que se leyó de disco, todavía sin aplicar a las listas compartidas. */
    private class CatalogoLeido(
        val live: List<ContentItem>,
        val movies: List<ContentItem>,
        val series: List<ContentItem>
    )

    /**
     * Solo lee y parsea el JSON de los tres bloques -sin tocar [live]/[movies]/
     * [series] ni ningún otro campo compartido. Pensada para poder correr en
     * [ioExecutor] (un hilo aparte) sin ninguna carrera: no lee ni escribe
     * nada que el hilo principal pueda estar usando al mismo tiempo.
     */
    private fun leerDeDiscoPuro(context: Context): CatalogoLeido? {
        val prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val stamp = prefs.getString(CACHE_KEY_SERVER, null) ?: return null
        val serverNow = "${Session.server(context)}|${Session.username(context)}"
        if (stamp != serverNow) return null   // caché de otro sistema, no sirve

        val type = object : TypeToken<List<ContentItem>>() {}.type
        return try {
            val liveList   = leerBloque(context, "live", type) ?: return null
            val moviesList = leerBloque(context, "movies", type) ?: return null
            val seriesList = leerBloque(context, "series", type) ?: return null
            if (liveList.isEmpty() && moviesList.isEmpty() && seriesList.isEmpty()) return null
            CatalogoLeido(liveList, moviesList, seriesList)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Vuelca lo ya leído a las listas compartidas. SIEMPRE en el hilo
     * principal (ver [loadFromDiskAsync]): es la única parte de la carga
     * desde disco que toca estado compartido, así que es la única que
     * necesita correr donde nadie más puede estar leyéndolo a la vez.
     */
    private fun aplicarLeidoDeDisco(context: Context, leido: CatalogoLeido) {
        live.addAll(leido.live); movies.addAll(leido.movies); series.addAll(leido.series)
        liveVersion++
        stampServer = Session.server(context).trim().trimEnd('/')
        stampUser   = Session.username(context)
    }

    /**
     * Igual que [loadFromDisk], pero parseando el JSON en [ioExecutor] (un
     * hilo aparte) y volcando el resultado a las listas compartidas ya en el
     * hilo principal, antes de avisar por [onDone] -así quien llama puede
     * tocar vistas sin preocuparse por eso, y las listas [live]/[movies]/
     * [series] nunca se mutan desde el hilo de fondo (esa mutación,
     * sin sincronizar, podía chocar con una lectura simultánea del hilo
     * principal -adaptador, buscador- y terminar en una
     * ConcurrentModificationException o en datos a medio escribir). Ver el
     * porqué de mover esto del hilo principal en el comentario de
     * [loadFromDisk].
     */
    fun loadFromDiskAsync(context: Context, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        ioExecutor.execute {
            val leido = leerDeDiscoPuro(app)
            ui.post {
                if (leido != null) aplicarLeidoDeDisco(app, leido)
                onDone(leido != null)
            }
        }
    }

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

        // Mientras hay una descarga en curso NO se da por fresco aunque la
        // anterior sea reciente: los bloques ya se vaciaron, y avisar
        // "listo" en ese momento entregaba listas vacías.
        if (isFresh(ctx) && !force && !loading) {
            onUpdate(false)
            return
        }

        if (!listeners.contains(onUpdate)) listeners.add(onUpdate)
        if (loading) return

        loading = true
        lastError = null
        live.clear(); movies.clear(); series.clear()
        liveVersion++
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
                if (block == Block.LIVE) liveVersion++
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
            // Guardar en disco para tener datos offline la próxima vez. En un
            // hilo aparte a propósito: esto se llama desde el callback de
            // Retrofit, que en Android corre en el hilo principal, y
            // serializar un catálogo grande ahí mismo es justo lo que
            // provocaba el OutOfMemoryError (ver la nota grande junto a
            // saveToDisk). El catálogo en memoria ([live]/[movies]/[series])
            // ya está listo y usable de inmediato para quien esté escuchando
            // (broadcast se manda ya, sin esperar a que termine de guardarse).
            if (!isEmpty) {
                val app = context.applicationContext
                val liveSnap = live.toList()
                val moviesSnap = movies.toList()
                val seriesSnap = series.toList()
                ioExecutor.execute { saveToDisk(app, liveSnap, moviesSnap, seriesSnap) }
            }
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

    /** Las [limit] películas más recientes, excluyendo categorías con control parental. */
    fun newestMovies(context: Context, limit: Int = 20): List<ContentItem> {
        val visible = movies.filter { !Parental.isCategoryLocked(context, it.categoryId) }
        val conFecha = visible.filter { it.added > 0 }
        return if (conFecha.isNotEmpty()) {
            conFecha.sortedByDescending { it.added }.take(limit)
        } else {
            visible.sortedByDescending { it.id }.take(limit)
        }
    }

    /** Las [limit] series más recientes, excluyendo categorías con control parental. */
    fun newestSeries(context: Context, limit: Int = 20): List<ContentItem> {
        val visible = series.filter { !Parental.isCategoryLocked(context, it.categoryId) }
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
        liveVersion++
        loadedAt = 0L
        loading = false
        lastError = null
        stampServer = ""
        stampUser = ""
    }
}
