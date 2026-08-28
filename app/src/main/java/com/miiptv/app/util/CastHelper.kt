package com.miiptv.app.util

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage

/**
 * Todo lo de Chromecast en un solo lugar, para que PlayerActivity no tenga
 * que saber los detalles del SDK de Cast.
 *
 * ---------------------------------------------------------------------------
 * POR QUÉ TODO ACÁ ESTÁ ENVUELTO EN try/catch Y DEVUELVE NULL
 *
 * Esta app corre sobre todo en decos de Android TV de IPTV, y una buena
 * parte de esos aparatos (sobre todo los chinos genéricos, tipo los que usan
 * chip Amlogic o Allwinner) NO TRAEN Google Play Services instalado -- ni
 * falta que les hace para andar como reproductor de IPTV. El SDK de Cast
 * depende por completo de Play Services: `CastContext.getSharedInstance()`
 * puede tirar una excepción, no devolver null, si no está disponible.
 *
 * Antes de este archivo la app no tocaba ni una API de Google Play Services;
 * agregar Cast sin este cuidado significa que la pantalla del reproductor
 * podría crashear al abrirse en cualquiera de esos aparatos sin GMS. Por eso
 * [isAvailable] se revisa antes de cualquier otra cosa, y todo lo demás
 * vuelve `null`/no hace nada si no hay Play Services, en vez de asumir que
 * siempre está.
 * ---------------------------------------------------------------------------
 */
object CastHelper {

    fun isAvailable(context: Context): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS
    } catch (t: Throwable) {
        false
    }

    private fun sharedInstance(context: Context): CastContext? {
        if (!isAvailable(context)) return null
        return try {
            CastContext.getSharedInstance(context)
        } catch (t: Throwable) {
            null
        }
    }

    fun sessionManager(context: Context): SessionManager? = sharedInstance(context)?.sessionManager

    fun currentSession(context: Context): CastSession? =
        sessionManager(context)?.currentCastSession?.takeIf { it.isConnected }

    fun isCasting(context: Context): Boolean = currentSession(context) != null

    fun deviceName(context: Context): String? = currentSession(context)?.castDevice?.friendlyName

    /**
     * Adivina el tipo MIME para el receptor a partir de la extensión real del
     * archivo (containerExtension, cuando lo hay) o, si no, de la propia URL.
     *
     * OJO: el receptor "por defecto" de Google (ver CastOptionsProvider) sabe
     * bien HLS (.m3u8), DASH y MP4. Con TS crudo (.ts, típico de canales en
     * vivo mal configurados) o MKV el soporte depende del modelo de
     * Chromecast/Google TV y no siempre funciona -- no hay forma de
     * garantizarlo desde acá, es una limitación del receptor por defecto, no
     * de esta app. Un receptor propio (ver CastOptionsProvider) podría
     * resolverlo con un decodificador de MPEG-TS a medida, pero eso ya es un
     * proyecto aparte.
     */
    fun mimeTypeFor(url: String, containerExtension: String?): String {
        val ext = containerExtension?.lowercase()?.trim()?.takeIf { it.isNotEmpty() }
            ?: url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (ext) {
            "m3u8" -> "application/x-mpegurl"
            "mpd" -> "application/dash+xml"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            // La mayoría de los canales en vivo de Xtream son HLS aunque la URL
            // no termine en .m3u8 (el panel la arma con parámetros por el medio).
            else -> "application/x-mpegurl"
        }
    }

    /**
     * Manda el stream actual al receptor y lo pone a reproducir.
     *
     * @param live true para Canales/PPV (streamType LIVE, sin cuenta regresiva
     *        de duración); false para Películas/Series/Radio (BUFFERED).
     */
    fun load(
        session: CastSession,
        url: String,
        title: String,
        posterUrl: String?,
        mimeType: String,
        live: Boolean,
        startPositionMs: Long = 0L
    ) {
        val metadata = MediaMetadata(
            if (live) MediaMetadata.MEDIA_TYPE_GENERIC else MediaMetadata.MEDIA_TYPE_MOVIE
        ).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            if (!posterUrl.isNullOrBlank()) {
                runCatching { addImage(WebImage(Uri.parse(posterUrl))) }
            }
        }
        val mediaInfo = MediaInfo.Builder(url)
            .setStreamType(if (live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mimeType)
            .setMetadata(metadata)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime(startPositionMs)
            .build()
        runCatching { session.remoteMediaClient?.load(request) }
    }
}
