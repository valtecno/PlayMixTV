package com.miiptv.app.util

import android.content.Context
import android.util.Base64
import com.miiptv.app.api.EpgListing
import com.miiptv.app.api.EpgResponse
import com.miiptv.app.api.Session
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * "Qué se está viendo ahora (y qué sigue)" por canal, nada más: sin grilla de
 * horarios ni programación futura más allá del próximo programa. Xtream
 * expone eso con get_short_epg (stream_id + limit=2).
 *
 * Dos cuidados especiales, porque son justo los que hacen que un EPG se vea
 * "desfasado":
 *
 *  1. HORARIO: el texto start/end que manda el panel viene formateado con el
 *     huso horario que tenga configurado ESE panel, no el del país donde se
 *     instaló la app. Por eso acá no se usa: se trabaja con start_timestamp/
 *     stop_timestamp (unix epoch, sin huso horario) y se formatea la hora a
 *     mostrar con el horario del propio dispositivo (TimeZone.getDefault()).
 *
 *  2. VIGENCIA: qué programa es "el actual" no se decide por la posición en
 *     la lista ni por el campo now_playing del panel (que depende del reloj
 *     DEL PANEL), sino comparando start_timestamp/stop_timestamp contra la
 *     hora actual del dispositivo. Si no se puede verificar que el primer
 *     resultado sigue vigente, se prefiere no mostrar nada antes que mostrar
 *     un programa que ya terminó.
 *
 * La lista de canales dispara una consulta por cada fila que se enfoca o
 * recicla; sin caché eso repetiría la misma consulta al panel con solo hacer
 * scroll. Lo que se cachea son los listados crudos (no el texto ya armado):
 * la vigencia se recalcula contra la hora actual en cada lectura, así una
 * entrada cacheada nunca queda mostrando un programa vencido.
 */
object Epg {

    private data class Entry(val listings: List<EpgListing>, val fetchedAt: Long)

    /** Cuánto se reutiliza la respuesta del panel antes de volver a pedirla. */
    private const val TTL_MS = 3 * 60 * 1000L

    private val cache = HashMap<Int, Entry>()
    // El callback ahora recibe la lista cruda de EpgListing en vez del
    // string ya formateado: así nowPlaying y nextPlaying pueden registrarse
    // para el mismo canal y cada uno extrae lo que le corresponde, con una
    // sola llamada de red compartida.
    private val pending = HashMap<Int, MutableList<(List<EpgListing>) -> Unit>>()

    /**
     * Pide (o devuelve de caché) "ahora / a continuación" del canal [streamId],
     * ya armado como un único texto listo para mostrar. [onResult] llega en el
     * hilo principal, con null si no hay nada vigente que mostrar (canal sin
     * EPG cargado en el panel, error de red, programa vencido, etc.) — el
     * llamador entonces oculta el subtítulo en vez de mostrar algo vacío o
     * desfasado.
     */
    fun nowPlaying(context: Context, streamId: Int, onResult: (String?) -> Unit) {
        val cached = cache[streamId]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.fetchedAt < TTL_MS) {
            onResult(renderNow(cached.listings))
            return
        }

        val enEspera = pending.getOrPut(streamId) { mutableListOf() }
        enEspera.add { listings -> onResult(renderNow(listings)) }
        if (enEspera.size > 1) return

        Session.api(context.applicationContext)
            .getShortEpg(Session.username(context), Session.password(context), streamId = streamId)
            .enqueue(object : Callback<EpgResponse> {
                override fun onResponse(call: Call<EpgResponse>, response: Response<EpgResponse>) {
                    val listados = response.body()?.epgListings.orEmpty()
                    cache[streamId] = Entry(listados, System.currentTimeMillis())
                    pending.remove(streamId).orEmpty().forEach { it(listados) }
                }

                override fun onFailure(call: Call<EpgResponse>, t: Throwable) {
                    pending.remove(streamId).orEmpty().forEach { it(emptyList()) }
                }
            })
    }

    /**
     * Solo el programa que sigue, para mostrarlo separado debajo del preview.
     * Reutiliza la misma caché que [nowPlaying]: si ya se cargó el EPG del
     * canal para la fila de la lista, esta llamada es instantánea y sin red.
     */
    fun nextPlaying(context: Context, streamId: Int, onResult: (String?) -> Unit) {
        val cached = cache[streamId]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.fetchedAt < TTL_MS) {
            onResult(renderNext(cached.listings))
            return
        }
        // Si todavía no hay caché, nowPlaying() la cargará; se encola después
        val enEspera = pending.getOrPut(streamId) { mutableListOf() }
        enEspera.add { listings -> onResult(renderNext(listings)) }
        if (enEspera.size > 1) return

        Session.api(context.applicationContext)
            .getShortEpg(Session.username(context), Session.password(context), streamId = streamId)
            .enqueue(object : Callback<EpgResponse> {
                override fun onResponse(call: Call<EpgResponse>, response: Response<EpgResponse>) {
                    val listados = response.body()?.epgListings.orEmpty()
                    cache[streamId] = Entry(listados, System.currentTimeMillis())
                    pending.remove(streamId).orEmpty().forEach { it(listados) }
                }

                override fun onFailure(call: Call<EpgResponse>, t: Throwable) {
                    pending.remove(streamId).orEmpty().forEach { it(emptyList()) }
                }
            })
    }

    /** Arma "HH:mm–HH:mm Título · Luego: Título (HH:mm)" a partir de lo vigente ahora mismo. */
    private fun renderNow(listados: List<EpgListing>): String? {
        val ahoraEpoch = System.currentTimeMillis() / 1000
        val idxActual = listados.indexOfFirst { estaVigente(it, ahoraEpoch) }

        val actual = listados.getOrNull(idxActual)
        val proximo = if (idxActual >= 0) {
            listados.getOrNull(idxActual + 1)
        } else {
            listados.firstOrNull { it.startTimestamp > ahoraEpoch }
        }

        val partes = mutableListOf<String>()
        actual?.tituloLegible()?.let { titulo ->
            val rango = rangoHorario(actual)
            partes.add(if (rango.isNotEmpty()) "$rango $titulo" else titulo)
        }
        proximo?.tituloLegible()?.let { titulo ->
            val hora = horaLocal(proximo.startTimestamp)
            partes.add(if (hora.isNotEmpty()) "Luego: $titulo ($hora)" else "Luego: $titulo")
        }

        return partes.joinToString("  ·  ").ifBlank { null }
    }

    /** Solo el programa que sigue (para mostrarlo en una línea separada en el preview). */
    private fun renderNext(listados: List<EpgListing>): String? {
        val ahoraEpoch = System.currentTimeMillis() / 1000
        val idxActual = listados.indexOfFirst { estaVigente(it, ahoraEpoch) }
        val proximo = if (idxActual >= 0) {
            listados.getOrNull(idxActual + 1)
        } else {
            listados.firstOrNull { it.startTimestamp > ahoraEpoch }
        }
        return proximo?.tituloLegible()?.let { titulo ->
            val hora = horaLocal(proximo.startTimestamp)
            if (hora.isNotEmpty()) "$titulo ($hora)" else titulo
        }
    }

    /** true si [listado] cubre el instante [ahoraEpoch], según sus propios timestamps epoch. */
    private fun estaVigente(listado: EpgListing, ahoraEpoch: Long): Boolean {
        val inicio = listado.startTimestamp
        if (inicio <= 0L) return false
        val fin = listado.stopTimestamp
        return ahoraEpoch >= inicio && (fin <= 0L || ahoraEpoch < fin)
    }

    private fun rangoHorario(listado: EpgListing): String {
        val inicio = horaLocal(listado.startTimestamp)
        val fin = horaLocal(listado.stopTimestamp)
        return when {
            inicio.isEmpty() -> ""
            fin.isEmpty() -> inicio
            else -> "$inicio–$fin"
        }
    }

    /** Timestamp epoch -> hora "HH:mm" en el horario del propio dispositivo. */
    private fun horaLocal(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return ""
        val formato = SimpleDateFormat("HH:mm", Locale.getDefault())
        formato.timeZone = TimeZone.getDefault()
        return formato.format(Date(epochSeconds * 1000))
    }

    private fun EpgListing.tituloLegible(): String? = title?.decodeEpgText()

    /** Xtream manda título y descripción en base64; si algún panel no lo hace, se devuelve tal cual. */
    private fun String.decodeEpgText(): String? {
        val decodificado = runCatching {
            String(Base64.decode(this, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(this).trim()
        return decodificado.ifBlank { null }
    }
}
