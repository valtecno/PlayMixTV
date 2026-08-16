package com.miiptv.app.api

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Guarda los datos de conexión del usuario (servidor, usuario, contraseña)
 * y construye el cliente Retrofit y las URLs de reproducción.
 */
object Session {
    private const val PREFS = "miiptv_prefs"

    /**
     * Agente que la app declara ante los servidores, tanto en la API como al
     * reproducir. Algunos paneles filtran por este valor.
     */
    const val USER_AGENT = "PlayMix TV - VIP"

    /*
     * Tiempos de espera.
     *
     * OkHttp trae 10 segundos por defecto en conexión, lectura y escritura, y
     * eso alcanza para un panel chico pero NO para el Sistema XL: cuando se
     * pide el catálogo completo (get_vod_streams sin categoría), el panel tarda
     * decenas de segundos solo en armar la consulta antes de mandar el primer
     * byte. A los 10 segundos OkHttp cortaba con SocketTimeoutException y la
     * pantalla quedaba vacía. Este era el motivo real de "no carga el contenido".
     *
     * readTimeout mide el hueco entre bytes, NO la descarga entera. Eso es
     * justamente lo que se quiere acá: corta si el servidor se cuelga, pero
     * deja terminar una descarga larga aunque el enlace sea lento.
     *
     * callTimeout queda DESACTIVADO a propósito. Con 300 s puestos como tope
     * total, el catálogo de películas del Sistema XL (decenas de MB) llegaba
     * completo en el emulador de PC pero se cortaba en el teléfono, donde el
     * mismo archivo tarda más. El síntoma era "0 películas" sin ningún error.
     */
    private const val CONNECT_TIMEOUT_S = 20L
    private const val READ_TIMEOUT_S = 120L
    private const val WRITE_TIMEOUT_S = 30L
    private const val CALL_TIMEOUT_S = 0L   // 0 = sin tope total

    /** Caché en disco de las respuestas del panel (JSON), en bytes. */
    private const val HTTP_CACHE_BYTES = 48L * 1024 * 1024

    /** Cuánto se reutiliza una respuesta guardada antes de volver a pedirla. */
    private const val CACHE_SECONDS = 10 * 60

    /** Cliente HTTP compartido, con el agente propio en todas las peticiones. */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    // ---------------- Cliente y API cacheados ----------------

    @Volatile private var diskCache: Cache? = null
    @Volatile private var xtreamClient: OkHttpClient? = null
    @Volatile private var cachedApi: XtreamApi? = null
    @Volatile private var cachedApiFor: String = ""

    /**
     * Cliente para el panel: el mismo pool de conexiones que httpClient, más
     * caché en disco. Los paneles Xtream mandan "no-cache" en todo, así que se
     * reescribe la cabecera de la respuesta para poder guardar los listados:
     * volver a entrar a la app dentro de los 10 minutos ya no vuelve a bajar
     * los 80 MB del catálogo del Sistema XL.
     */
    private fun clientFor(context: Context): OkHttpClient {
        xtreamClient?.let { return it }
        synchronized(this) {
            xtreamClient?.let { return it }
            val cache = Cache(java.io.File(context.applicationContext.cacheDir, "xtream_http"), HTTP_CACHE_BYTES)
            diskCache = cache
            val built = httpClient.newBuilder()
                .cache(cache)
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val esListado = chain.request().url.queryParameter("action") != null
                    if (esListado) {
                        response.newBuilder()
                            .removeHeader("Pragma")
                            .removeHeader("Expires")
                            .header("Cache-Control", "public, max-age=$CACHE_SECONDS")
                            .build()
                    } else {
                        response
                    }
                }
                .build()
            xtreamClient = built
            return built
        }
    }

    /** Borra el JSON guardado en disco. Hace E/S: llamar fuera del hilo principal. */
    fun dropHttpCache(context: Context) {
        clientFor(context)
        runCatching { diskCache?.evictAll() }
    }

    fun save(context: Context, server: String, username: String, password: String) {
        val clean = normalize(server)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("server", clean)
            .putString("username", username.trim())
            .putString("password", password.trim())
            .apply()
        // El Retrofit guardado apunta al servidor anterior: hay que rehacerlo o
        // la app seguiría pidiéndole el catálogo al panel que acabamos de dejar.
        invalidateApi()
    }

    /** Deja la URL siempre en la misma forma: sin espacios y sin barra final. */
    private fun normalize(url: String): String = url.trim().trimEnd('/')

    fun server(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("server", "") ?: ""

    fun username(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("username", "") ?: ""

    fun password(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("password", "") ?: ""

    fun isLoggedIn(context: Context): Boolean =
        server(context).isNotBlank() && username(context).isNotBlank()

    fun logout(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        invalidateApi()
    }

    private fun invalidateApi() {
        synchronized(this) {
            cachedApi = null
            cachedApiFor = ""
        }
    }

    /**
     * Cliente de la API del panel.
     *
     * Antes se construía un Retrofit nuevo en cada llamada (y hay llamadas en
     * cada chip de categoría que se toca). Ahora se arma una sola vez por
     * servidor+usuario y se reutiliza; si se cambia de sistema o de cuenta, la
     * clave deja de coincidir y se rehace solo.
     */
    fun api(context: Context): XtreamApi {
        val clave = normalize(server(context)) + "|" + username(context)
        cachedApi?.let { if (cachedApiFor == clave) return it }
        synchronized(this) {
            cachedApi?.let { if (cachedApiFor == clave) return it }
            val creado = Retrofit.Builder()
                .baseUrl(normalize(server(context)) + "/")
                .client(clientFor(context))
                // El de streaming va primero: solo atiende los tipos del catálogo
                // y le deja todo lo demás a Gson.
                .addConverterFactory(XtreamStream.Factory)
                .addConverterFactory(GsonConverterFactory.create(XtreamGson.instance))
                .build()
                .create(XtreamApi::class.java)
            cachedApi = creado
            cachedApiFor = clave
            return creado
        }
    }

    /** URL directa del stream en vivo, formato estándar Xtream Codes. */
    fun liveStreamUrl(context: Context, streamId: Int, extension: String = "m3u8"): String {
        return "${normalize(server(context))}/live/${username(context)}/${password(context)}/$streamId.$extension"
    }

    /** URL directa de una película (VOD). */
    fun vodStreamUrl(context: Context, streamId: Int, extension: String = "mp4"): String {
        return "${normalize(server(context))}/movie/${username(context)}/${password(context)}/$streamId.$extension"
    }

    /** URL directa de un episodio de serie. */
    fun seriesEpisodeUrl(context: Context, episodeId: String, extension: String = "mp4"): String {
        return "${normalize(server(context))}/series/${username(context)}/${password(context)}/$episodeId.$extension"
    }
}
