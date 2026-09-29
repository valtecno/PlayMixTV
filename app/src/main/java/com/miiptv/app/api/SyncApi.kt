package com.miiptv.app.api

import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Copia de seguridad en la nube de Favoritos/Continuar viendo/Historial.
 *
 * Vive en el mismo servidor propio que ya valida el código de acceso (ver
 * PanelApi/api.php): un endpoint nuevo, sync.php, que guarda y devuelve un
 * blob de JSON por cuenta. "cuenta" es la misma clave (servidor + usuario
 * Xtream) que ya separa los archivos locales de cada cuenta en
 * Favorites/ContinueWatching/History — no hace falta inventar otro
 * identificador ni tocar el flujo de login.
 *
 *   GET  sync.php?accion=obtener&cuenta=X   → {"status":"ok","datos":"<json o null>"}
 *   POST sync.php  accion=guardar&cuenta=X&datos=<json>  → {"status":"ok"}
 *
 * El "datos" que se sube y se baja es el JSON de [com.miiptv.app.util.DataSync.Payload]
 * tal cual, sin interpretarlo del lado del servidor: ese archivo PHP solo
 * necesita guardar y devolver un string por cuenta (ver server/sync.php.txt
 * en el repo para una implementación de referencia).
 */
interface SyncApi {

    @GET("sync.php")
    fun obtener(
        @Query("accion") accion: String = "obtener",
        @Query("cuenta") cuenta: String
    ): Call<SyncGetResponse>

    @FormUrlEncoded
    @POST("sync.php")
    fun guardar(
        @Field("accion") accion: String = "guardar",
        @Field("cuenta") cuenta: String,
        @Field("datos") datos: String
    ): Call<SyncPostResponse>

    companion object {
        private const val BASE_URL = "https://valtecno.cl/disc/paneltv/"

        // Corto a propósito: restore() se llama justo antes de abrir el
        // Inicio (login, o cambio rápido de cuenta) y no debería demorar esa
        // pantalla más que unos segundos aunque la red esté mala. Con los
        // tiempos por defecto de OkHttp (~10s) un usuario con mal internet
        // se quedaba mirando el círculo de carga mucho más de lo razonable.
        private const val TIMEOUT_S = 6L

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .build()
        }

        fun create(): SyncApi = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SyncApi::class.java)

        val instance: SyncApi by lazy { create() }
    }
}
