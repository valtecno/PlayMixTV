package com.miiptv.app.api

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * Xtream Codes usa un único endpoint (player_api.php) con distintos
 * valores de "action" para autenticar, listar categorías y contenidos.
 */
interface XtreamApi {

    @GET("player_api.php")
    fun login(
        @Query("username") username: String,
        @Query("password") password: String
    ): Call<LoginResponse>

    // ---- TV en vivo ----
    @GET("player_api.php")
    fun getLiveCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_categories"
    ): Call<List<Category>>

    @GET("player_api.php")
    fun getLiveStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_streams",
        @Query("category_id") categoryId: String? = null,
        /** "no-cache" fuerza ir al servidor y saltear la copia guardada en disco. */
        @Header("Cache-Control") cacheControl: String? = null
    ): Call<List<LiveStream>>

    // ---- Películas (VOD) ----
    @GET("player_api.php")
    fun getVodCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_categories"
    ): Call<List<Category>>

    @GET("player_api.php")
    fun getVodStreams(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_streams",
        @Query("category_id") categoryId: String? = null,
        /** "no-cache" fuerza ir al servidor y saltear la copia guardada en disco. */
        @Header("Cache-Control") cacheControl: String? = null
    ): Call<List<VodStream>>

    @GET("player_api.php")
    fun getVodInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_info",
        @Query("vod_id") vodId: Int
    ): Call<VodInfoResponse>

    // ---- Series ----
    @GET("player_api.php")
    fun getSeriesCategories(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_categories"
    ): Call<List<Category>>

    @GET("player_api.php")
    fun getSeries(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series",
        @Query("category_id") categoryId: String? = null,
        /** "no-cache" fuerza ir al servidor y saltear la copia guardada en disco. */
        @Header("Cache-Control") cacheControl: String? = null
    ): Call<List<SeriesItem>>

    @GET("player_api.php")
    fun getSeriesInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_info",
        @Query("series_id") seriesId: Int
    ): Call<SeriesInfoResponse>

    // ---- Catálogo completo (lectura en streaming) ----
    //
    // Mismos endpoints que los de arriba, pero devolviendo los tipos de
    // XtreamStream. Retrofit los parsea de a un registro por vez y arma
    // directamente el ContentItem final, sin la lista intermedia que hacía
    // reventar el bloque de películas en teléfonos reales.

    @GET("player_api.php")
    fun getLiveCatalog(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_live_streams",
        @Header("Cache-Control") cacheControl: String? = null
    ): Call<XtreamStream.LiveList>

    @GET("player_api.php")
    fun getVodCatalog(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_vod_streams",
        @Header("Cache-Control") cacheControl: String? = null
    ): Call<XtreamStream.MovieList>

    @GET("player_api.php")
    fun getSeriesCatalog(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series",
        @Header("Cache-Control") cacheControl: String? = null
    ): Call<XtreamStream.SeriesList>
}
