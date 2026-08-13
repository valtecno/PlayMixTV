package com.miiptv.app.api

import retrofit2.Call
import retrofit2.http.GET
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
        @Query("category_id") categoryId: String? = null
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
        @Query("category_id") categoryId: String? = null
    ): Call<List<VodStream>>

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
        @Query("category_id") categoryId: String? = null
    ): Call<List<SeriesItem>>

    @GET("player_api.php")
    fun getSeriesInfo(
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String = "get_series_info",
        @Query("series_id") seriesId: Int
    ): Call<SeriesInfoResponse>
}
