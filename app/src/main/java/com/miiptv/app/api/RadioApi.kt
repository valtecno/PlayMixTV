package com.miiptv.app.api

import com.google.gson.annotations.SerializedName
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Una emisora devuelta por Radio Browser, la base de datos pública y abierta
 * de radios de internet (https://api.radio-browser.info).
 */
data class RadioStation(
    @SerializedName("stationuuid") val uuid: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("url_resolved") val urlResolved: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("favicon") val favicon: String? = null,
    @SerializedName("countrycode") val countryCode: String? = null,
    @SerializedName("tags") val tags: String? = null,
    @SerializedName("codec") val codec: String? = null,
    @SerializedName("bitrate") val bitrate: Int? = null
) {
    /** URL que realmente se puede reproducir (url_resolved ya sigue las redirecciones). */
    val playable: String? get() = urlResolved?.takeIf { it.isNotBlank() } ?: url?.takeIf { it.isNotBlank() }
}

interface RadioApi {

    /** Emisoras de un país, las más votadas primero y descartando las caídas. */
    @GET("json/stations/bycountrycodeexact/{code}")
    fun byCountry(
        @Path("code") countryCode: String,
        @Query("limit") limit: Int = 150,
        @Query("order") order: String = "votes",
        @Query("reverse") reverse: Boolean = true,
        @Query("hidebroken") hideBroken: Boolean = true
    ): Call<List<RadioStation>>
}
