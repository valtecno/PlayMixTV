package com.miiptv.app.api

import com.google.gson.annotations.SerializedName

data class LoginResponse(
    @SerializedName("user_info") val userInfo: UserInfo?
)

data class UserInfo(
    @SerializedName("auth") val auth: Int?,
    @SerializedName("status") val status: String?
)

data class Category(
    @SerializedName("category_id") val categoryId: String,
    @SerializedName("category_name") val categoryName: String
)

data class LiveStream(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("category_id") val categoryId: String?
)

data class VodStream(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String,
    @SerializedName("stream_id") val streamId: Int,
    @SerializedName("stream_icon") val streamIcon: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("container_extension") val containerExtension: String?
)

data class SeriesItem(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String,
    @SerializedName("series_id") val seriesId: Int,
    @SerializedName("cover") val cover: String?,
    @SerializedName("category_id") val categoryId: String?
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
    val containerExtension: String? = null
)

fun LiveStream.toContentItem() = ContentItem(streamId, name, streamIcon, categoryId, ContentType.LIVE)
fun VodStream.toContentItem() = ContentItem(streamId, name, streamIcon, categoryId, ContentType.MOVIE, containerExtension)
fun SeriesItem.toContentItem() = ContentItem(seriesId, name, cover, categoryId, ContentType.SERIES)
