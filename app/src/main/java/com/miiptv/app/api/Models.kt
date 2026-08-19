package com.miiptv.app.api

import com.google.gson.annotations.SerializedName

data class LoginResponse(
    @SerializedName("user_info") val userInfo: UserInfo?
)

data class UserInfo(
    @SerializedName("auth") val auth: Int?,
    @SerializedName("status") val status: String?,
    /** Vencimiento de la cuenta (unix, en segundos). Puede venir vacío en cuentas ilimitadas. */
    @SerializedName("exp_date") val expDate: String? = null
)

data class Category(
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("category_name") val categoryName: String
)

data class LiveStream(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("added") val added: String? = null
)

data class VodStream(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("added") val added: String? = null
)

data class SeriesItem(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("series_id") val seriesId: Int,
    @SerializedName("cover") val cover: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("last_modified") val lastModified: String? = null
)

/** Respuesta de get_vod_info: ficha de una película. */
data class VodInfoResponse(
    @SerializedName("info") val info: VodInfo?,
    @SerializedName("movie_data") val movieData: VodMovieData?
)

data class VodInfo(
    @SerializedName(value = "plot", alternate = ["description"]) val plot: String? = null,
    @SerializedName("cast") val cast: String? = null,
    @SerializedName("director") val director: String? = null,
    @SerializedName("genre") val genre: String? = null,
    @SerializedName(value = "releasedate", alternate = ["release_date"]) val releaseDate: String? = null,
    @SerializedName("rating") val rating: String? = null,
    @SerializedName("duration") val duration: String? = null,
    @SerializedName(value = "movie_image", alternate = ["cover_big"]) val image: String? = null
)

data class VodMovieData(
    @SerializedName("stream_id") val streamId: Int? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("container_extension") val containerExtension: String? = null
)

data class SeriesInfoResponse(
    @SerializedName("episodes") val episodes: Map<String, List<Episode>>?
)

data class Episode(
    @SerializedName("id") val id: String,
    @SerializedName("episode_num") val episodeNum: Int?,
    @SerializedName("title") val title: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("season") val season: Int?
)

/**
 * Respuesta de get_short_epg: la mini-guía que da Xtream para un canal
 * (normalmente el programa actual y el siguiente). Acá solo se usa el
 * primer elemento, que es el que está al aire ahora.
 */
data class EpgResponse(
    @SerializedName("epg_listings") val epgListings: List<EpgListing>?
)

data class EpgListing(
    /** Viene codificado en base64, como manda el estándar Xtream. */
    @SerializedName("title") val title: String?,
    @SerializedName("start") val start: String?,
    @SerializedName("end") val end: String?,
    /**
     * Unix epoch (segundos), independiente del huso horario del panel. Es lo
     * único confiable para saber si un programa está vigente "ahora": el texto
     * de start/end viene formateado con el horario que tenga configurado el
     * panel, que no tiene por qué coincidir con el del país donde se instaló
     * la app.
     */
    @SerializedName("start_timestamp") val startTimestamp: Long = 0L,
    @SerializedName("stop_timestamp") val stopTimestamp: Long = 0L,
    @SerializedName("now_playing") val nowPlaying: Int?
)

/** Tipo de contenido, para poder usar una sola lista/adapter para todo. */
enum class ContentType { LIVE, MOVIE, SERIES }

/**
 * Representación unificada de un elemento de la lista (canal, película o serie),
 * para poder reutilizar el mismo RecyclerView/Adapter y el sistema de Favoritos
 * en los tres tipos de contenido.
 */
data class ContentItem(
    val id: Int,
    val name: String,
    val icon: String?,
    val categoryId: String?,
    val type: ContentType,
    val containerExtension: String? = null,
    /** Fecha de alta en el servidor (unix, segundos). 0 = desconocida. */
    val added: Long = 0L,
    /** URL de reproducción directa. Solo la usan las radios; el resto se arma con Session. */
    val streamUrl: String? = null
)

/** Convierte el "added"/"last_modified" de Xtream (texto) a unix seconds. */
private fun String?.toEpoch(): Long = this?.trim()?.toLongOrNull() ?: 0L

fun LiveStream.toContentItem() =
    ContentItem(streamId, name.orEmpty(), streamIcon, categoryId, ContentType.LIVE, null, added.toEpoch())

fun VodStream.toContentItem() =
    ContentItem(streamId, name.orEmpty(), streamIcon, categoryId, ContentType.MOVIE, containerExtension, added.toEpoch())

fun SeriesItem.toContentItem() =
    ContentItem(seriesId, name.orEmpty(), cover, categoryId, ContentType.SERIES, null, lastModified.toEpoch())
