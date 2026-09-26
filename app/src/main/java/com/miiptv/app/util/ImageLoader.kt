package com.miiptv.app.util

import android.content.Context
import android.os.StatFs
import com.miiptv.app.api.Session
import com.squareup.picasso.LruCache
import com.squareup.picasso.OkHttp3Downloader
import com.squareup.picasso.Picasso
import okhttp3.Cache
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Configuración única de Picasso para toda la app (logos de canales,
 * carátulas de películas y series, Inicio, fichas).
 *
 * Antes se usaba el Picasso "de fábrica", que para este caso rendía mal:
 *
 *  - **Caché en disco que casi nunca servía.** Los servidores de imágenes de
 *    los paneles suelen mandar "no-cache" / "Expires" vencido, y OkHttp
 *    respeta eso: cada vez que se abría la app se volvían a bajar TODAS las
 *    carátulas. Ahora se reescribe esa cabecera (igual que ya se hace con el
 *    JSON del panel en Session) y una imagen bajada queda 30 días en disco.
 *  - **Caché en disco chica** (tope de 50 MB): con el catálogo del Sistema XL
 *    se llenaba enseguida y se iban borrando las carátulas recién bajadas.
 *  - **Pocas descargas a la vez** (3, o 4 con Wi-Fi). Las imágenes vienen de
 *    muchos servidores distintos y son chicas: el cuello de botella es la
 *    espera de red, no el ancho de banda. Ahora van 6 en paralelo.
 *  - **Tiempos de espera de 120 s** heredados del cliente del panel (que los
 *    necesita para el catálogo XL). Un logo roto de un servidor caído dejaba
 *    un hilo trabado dos minutos; para imágenes, 10 s / 20 s alcanzan.
 *  - **Un pool de conexiones aparte.** Ahora comparte el de Session.httpClient.
 */
object ImageLoader {

    /** Tag de las imágenes de listas: se pausan mientras la lista se desplaza rápido. */
    const val TAG_LISTA = "playmix_lista"

    private const val DIAS_EN_DISCO = 30
    private const val MIN_DISCO = 20L * 1024 * 1024
    private const val MAX_DISCO = 150L * 1024 * 1024
    private const val HILOS = 6

    @Volatile private var listo = false
    @Volatile private var discoCache: Cache? = null

    /** Nombre de la carpeta de la caché en disco, dentro de cacheDir. */
    const val CARPETA_DISCO = "picasso-imagenes"

    /**
     * Vacía la caché de imágenes en disco sin borrar su carpeta a mano (eso,
     * con la caché abierta, la dejaba sin guardar nada hasta reiniciar la
     * app). Hace E/S: llamar fuera del hilo principal.
     */
    fun vaciarDisco() {
        runCatching { discoCache?.evictAll() }
    }

    /**
     * Instala la configuración como la instancia global de Picasso. Se llama
     * una sola vez, desde [com.miiptv.app.PlayMixApp], antes de que cualquier
     * pantalla use `Picasso.get()` (después de eso Picasso ya no permite
     * cambiar la instancia).
     */
    fun init(context: Context) {
        if (listo) return
        synchronized(this) {
            if (listo) return
            val app = context.applicationContext
            runCatching { Picasso.setSingletonInstance(build(app)) }
            listo = true
            // La caché del Picasso "de fábrica" ya no se usa: se borra una
            // vez, en segundo plano, para no dejar hasta 50 MB huérfanos.
            Thread {
                runCatching { File(app.cacheDir, "picasso-cache").deleteRecursively() }
            }.start()
        }
    }

    private fun build(context: Context): Picasso {
        val carpeta = File(context.cacheDir, CARPETA_DISCO).apply { mkdirs() }
        val cache = Cache(carpeta, tamanoDisco(carpeta))
        discoCache = cache

        val cliente = Session.httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .cache(cache)
            .addNetworkInterceptor { chain ->
                val respuesta = chain.proceed(chain.request())
                if (respuesta.isSuccessful) {
                    respuesta.newBuilder()
                        .removeHeader("Pragma")
                        .removeHeader("Expires")
                        .header("Cache-Control", "public, max-age=${DIAS_EN_DISCO * 24 * 60 * 60}")
                        .build()
                } else {
                    respuesta
                }
            }
            .build()

        val hilos = ThreadPoolExecutor(
            HILOS, HILOS, 30L, TimeUnit.SECONDS, LinkedBlockingQueue()
        ).apply { allowCoreThreadTimeOut(true) }

        return Picasso.Builder(context)
            .downloader(OkHttp3Downloader(cliente))
            .executor(hilos)
            .memoryCache(LruCache(tamanoMemoria()))
            .build()
    }

    /** ~2,5 % del espacio libre, entre 20 y 150 MB (los TV box suelen tener poco disco). */
    private fun tamanoDisco(carpeta: File): Long {
        val libre = runCatching { StatFs(carpeta.absolutePath).availableBytes }.getOrDefault(0L)
        return (libre / 40).coerceIn(MIN_DISCO, MAX_DISCO)
    }

    /**
     * Un 20 % del heap para bitmaps ya decodificados (Picasso usa 15 %). Con
     * largeHeap eso alcanza para volver a una lista recién vista sin
     * redecodificar nada.
     */
    private fun tamanoMemoria(): Int =
        (Runtime.getRuntime().maxMemory() / 5).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
