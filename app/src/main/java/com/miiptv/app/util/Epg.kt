package com.miiptv.app.util

import android.content.Context
import android.util.Base64
import com.miiptv.app.api.EpgResponse
import com.miiptv.app.api.Session
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * "Qué se está viendo ahora" por canal, nada más: sin grilla de horarios ni
 * programación futura. Xtream expone eso por canal con get_short_epg
 * (stream_id + limit=1), así que acá solo se pide el primer elemento.
 *
 * La lista de canales dispara una consulta por cada fila que se enfoca o
 * recicla; sin caché eso repetiría la misma consulta al panel una y otra vez
 * con solo hacer scroll. Se guarda el resultado unos minutos por canal y, si
 * ya hay una consulta en curso para ese mismo canal, las siguientes esperan
 * esa respuesta en vez de disparar otra.
 */
object Epg {

    private data class Entry(val title: String?, val fetchedAt: Long)

    /** Cuánto se reutiliza un resultado antes de volver a pedirlo al panel. */
    private const val TTL_MS = 3 * 60 * 1000L

    private val cache = HashMap<Int, Entry>()
    private val pending = HashMap<Int, MutableList<(String?) -> Unit>>()

    /**
     * Pide (o devuelve de caché) el programa actual del canal [streamId].
     * [onResult] llega en el hilo principal, con null si no hay dato (canal
     * sin EPG cargado en el panel, error de red, etc.) — el llamador decide
     * entonces ocultar el subtítulo en vez de mostrar algo vacío.
     */
    fun nowPlaying(context: Context, streamId: Int, onResult: (String?) -> Unit) {
        val cached = cache[streamId]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.fetchedAt < TTL_MS) {
            onResult(cached.title)
            return
        }

        val enEspera = pending.getOrPut(streamId) { mutableListOf() }
        enEspera.add(onResult)
        if (enEspera.size > 1) return // ya hay una consulta en curso; esta espera el mismo resultado

        Session.api(context.applicationContext)
            .getShortEpg(Session.username(context), Session.password(context), streamId = streamId)
            .enqueue(object : Callback<EpgResponse> {
                override fun onResponse(call: Call<EpgResponse>, response: Response<EpgResponse>) {
                    val titulo = response.body()?.epgListings?.firstOrNull()?.title?.decodeEpgText()
                    cache[streamId] = Entry(titulo, System.currentTimeMillis())
                    pending.remove(streamId).orEmpty().forEach { it(titulo) }
                }

                override fun onFailure(call: Call<EpgResponse>, t: Throwable) {
                    // No se cachea el error: un corte de red momentáneo no debe
                    // dejar el canal "sin programa" pegado varios minutos.
                    pending.remove(streamId).orEmpty().forEach { it(null) }
                }
            })
    }

    /** Xtream manda título y descripción en base64; si algún panel no lo hace, se devuelve tal cual. */
    private fun String.decodeEpgText(): String? {
        val decodificado = runCatching {
            String(Base64.decode(this, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(this).trim()
        return decodificado.ifBlank { null }
    }
}
