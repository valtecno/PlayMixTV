package com.miiptv.app.util

import android.content.Context
import com.miiptv.app.api.ContentItem
import com.miiptv.app.api.ContentType
import com.miiptv.app.api.RadioApi
import com.miiptv.app.api.RadioStation
import com.miiptv.app.api.Session
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Radios principales del mundo, tomadas de Radio Browser: una base de datos
 * pública, gratuita y de código abierto con decenas de miles de emisoras.
 *
 * No hay claves ni registro; solo piden identificarse con un User-Agent propio,
 * que es el mismo "PlayMix TV - VIP" que usa el resto de la app.
 *
 * Hay dos maneras de armar una lista:
 *
 *  - **Por país** ([Source.countryCode]): todas las emisoras de ese país, las
 *    más votadas primero. Es lo que usan los chips con bandera.
 *  - **Por nombre** ([Source.queries]): todas las emisoras de una marca. Así se
 *    arman Loca FM (con sus veintipico de estilos) y Tomorrowland, sin dejar
 *    direcciones de stream fijas en el código: si la emisora se muda de
 *    servidor, el directorio ya devuelve la nueva y la app no se entera.
 */
object RadioCatalog {

    /** Un chip de la sección Radios: un país o una marca. */
    data class Source(
        val flag: String,
        val name: String,
        val id: String,
        val genres: String,
        /** Código ISO del país, para las listas por país. */
        val countryCode: String? = null,
        /**
         * Textos a buscar en el nombre de la emisora. Son varios porque una
         * misma marca no siempre se registra igual: Loca FM tiene canales
         * cargados como "LOCA 80'S", sin el "FM" en el medio.
         */
        val queries: List<String> = emptyList(),
        /** Red de seguridad por si el directorio no devuelve nada. */
        val fallback: List<Fixed> = emptyList()
    ) {
        /** Las marcas se buscan por nombre; los países, por código. */
        val isCollection: Boolean get() = countryCode == null
    }

    /** Emisora con dirección fija, solo para los casos de respaldo. */
    data class Fixed(val name: String, val url: String, val icon: String? = null)

    /**
     * Chips de la sección, en el orden en que se muestran. Primero España y las
     * dos marcas pedidas; después el resto de los países.
     */
    val sources = listOf(
        Source(
            flag = "\uD83C\uDDEA\uD83C\uDDF8", name = "España", id = "radio_ES", countryCode = "ES",
            genres = "Los 40, Cadena SER, COPE, Rock FM, Kiss, Flaix, indie y flamenco"
        ),
        Source(
            flag = "\uD83C\uDFA7", name = "Loca FM", id = "radio_locafm",
            genres = "Dance, techno, house, trance, hard, remember y 80s — todos sus estilos",
            queries = listOf("loca fm", "locafm", "loca 80")
        ),
        Source(
            flag = "\uD83C\uDFAA", name = "Tomorrowland", id = "radio_tomorrowland",
            genres = "One World Radio: el sonido del festival, 24/7 y siempre mezclado",
            queries = listOf("tomorrowland", "one world radio"),
            fallback = listOf(
                Fixed(
                    "Tomorrowland One World Radio",
                    "https://playerservices.streamtheworld.com/api/livestream-redirect/ONE_WORLD_RADIO.mp3"
                )
            )
        ),
        Source("\uD83C\uDDFA\uD83C\uDDF8", "Estados Unidos", "radio_US", "Pop, rock, country, hip-hop, R&B, electrónica", "US"),
        Source("\uD83C\uDDF2\uD83C\uDDFD", "México", "radio_MX", "Regional mexicano, pop latino, banda, norteño", "MX"),
        Source("\uD83C\uDDE7\uD83C\uDDF7", "Brasil", "radio_BR", "Sertanejo, pop, funk brasileño, MPB", "BR"),
        Source("\uD83C\uDDE8\uD83C\uDDF4", "Colombia", "radio_CO", "Reggaetón, vallenato, pop latino, salsa", "CO"),
        Source("\uD83C\uDDE6\uD83C\uDDF7", "Argentina", "radio_AR", "Rock nacional, pop, urbano, electrónica", "AR"),
        Source("\uD83C\uDDE8\uD83C\uDDF1", "Chile", "radio_CL", "Pop, rock, urbano, música latina", "CL"),
        Source("\uD83C\uDDE8\uD83C\uDDE6", "Canadá", "radio_CA", "Pop, rock, country, música alternativa", "CA"),
        Source("\uD83C\uDDF5\uD83C\uDDEA", "Perú", "radio_PE", "Salsa, cumbia, pop, urbano", "PE"),
        Source("\uD83C\uDDFB\uD83C\uDDEA", "Venezuela", "radio_VE", "Salsa, merengue, pop latino, urbano", "VE"),
        Source("\uD83C\uDDEA\uD83C\uDDE8", "Ecuador", "radio_EC", "Pop latino, urbano, salsa, reggaetón", "EC"),
        Source("\uD83C\uDDE9\uD83C\uDDF4", "Rep. Dominicana", "radio_DO", "Bachata, merengue, urbano", "DO"),
        Source("\uD83C\uDDF5\uD83C\uDDF7", "Puerto Rico", "radio_PR", "Reguetón, trap latino, salsa, pop", "PR")
    )

    /** Espejos oficiales del servicio; si uno no responde se prueba el siguiente. */
    private val mirrors = listOf(
        "https://de1.api.radio-browser.info/",
        "https://nl1.api.radio-browser.info/",
        "https://at1.api.radio-browser.info/"
    )

    private val cache = mutableMapOf<String, List<ContentItem>>()
    private var pendingCall: Call<List<RadioStation>>? = null

    private fun apiFor(mirror: String): RadioApi = Retrofit.Builder()
        .baseUrl(mirror)
        .client(Session.httpClient)   // lleva el User-Agent "PlayMix TV - VIP"
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(RadioApi::class.java)

    fun categoryIdFor(source: Source) = source.id

    /**
     * Carga las emisoras de un chip. Si ya se pidieron antes en esta sesión,
     * responde al instante desde la caché.
     */
    fun load(
        context: Context,
        source: Source,
        onResult: (List<ContentItem>, error: String?) -> Unit
    ) {
        cache[source.id]?.let {
            onResult(it, null)
            return
        }
        pendingCall?.cancel()
        request(source, mirrorIndex = 0, onResult = onResult)
    }

    // ---------------- Peticiones al directorio ----------------

    private fun request(
        source: Source,
        mirrorIndex: Int,
        onResult: (List<ContentItem>, String?) -> Unit
    ) {
        if (mirrorIndex >= mirrors.size) {
            // Se acabaron los espejos: queda lo de respaldo, si la fuente tiene
            finish(source, emptyList(), onResult, offline = true)
            return
        }

        val api = apiFor(mirrors[mirrorIndex])
        val code = source.countryCode
        if (code != null) {
            enqueue(
                api.byCountry(code),
                onOk = { finish(source, it, onResult) },
                onFail = { request(source, mirrorIndex + 1, onResult) }
            )
        } else {
            runQuery(api, source, queryIndex = 0, acc = mutableListOf(), mirrorIndex, onResult)
        }
    }

    /**
     * Encadena las búsquedas por nombre de una marca y junta todo. Se hacen una
     * tras otra a propósito: son pocas y así no se abren varias conexiones al
     * directorio al mismo tiempo.
     */
    private fun runQuery(
        api: RadioApi,
        source: Source,
        queryIndex: Int,
        acc: MutableList<RadioStation>,
        mirrorIndex: Int,
        onResult: (List<ContentItem>, String?) -> Unit
    ) {
        if (queryIndex >= source.queries.size) {
            finish(source, acc, onResult)
            return
        }
        enqueue(
            api.searchByName(source.queries[queryIndex]),
            onOk = {
                acc.addAll(it)
                runQuery(api, source, queryIndex + 1, acc, mirrorIndex, onResult)
            },
            onFail = {
                // Si ya juntamos algo, sirve igual; si no, se prueba otro espejo
                if (acc.isNotEmpty()) finish(source, acc, onResult)
                else request(source, mirrorIndex + 1, onResult)
            }
        )
    }

    private fun enqueue(
        call: Call<List<RadioStation>>,
        onOk: (List<RadioStation>) -> Unit,
        onFail: () -> Unit
    ) {
        pendingCall = call
        call.enqueue(object : Callback<List<RadioStation>> {
            override fun onResponse(
                call: Call<List<RadioStation>>,
                response: Response<List<RadioStation>>
            ) {
                val body = response.body()
                if (response.isSuccessful && body != null) onOk(body) else onFail()
            }

            override fun onFailure(call: Call<List<RadioStation>>, t: Throwable) {
                if (call.isCanceled) return
                onFail()
            }
        })
    }

    /**
     * Limpia lo que llegó del directorio y lo entrega listo para la lista.
     *
     * En las marcas se quitan además los nombres repetidos: el directorio suele
     * traer la misma emisora cargada dos o tres veces con direcciones distintas,
     * y en una lista de estilos eso se ve como ruido. Se ordenan por nombre para
     * que los estilos se lean como un índice.
     */
    private fun finish(
        source: Source,
        stations: List<RadioStation>,
        onResult: (List<ContentItem>, String?) -> Unit,
        offline: Boolean = false
    ) {
        var limpias = stations
            .filter { !it.name.isNullOrBlank() && !it.playable.isNullOrBlank() }
            .distinctBy { it.playable }

        if (source.isCollection) {
            limpias = limpias
                .distinctBy { it.name!!.trim().lowercase() }
                .sortedBy { it.name!!.trim().lowercase() }
        }

        var items = limpias.map { it.toContentItem(source) }
        if (items.isEmpty()) items = source.fallback.map { it.toContentItem(source) }

        // Una lista vacía no se guarda: así el usuario puede reintentar
        // volviendo a tocar el chip, sin quedar pegado a un error puntual.
        if (items.isNotEmpty()) cache[source.id] = items

        val error = if (items.isEmpty() && offline) {
            "No se pudo conectar al directorio de radios"
        } else {
            null
        }
        onResult(items, error)
    }

    // ---------------- Conversión ----------------

    private fun RadioStation.toContentItem(source: Source) = ContentItem(
        // El id de Xtream es numérico; para las radios usamos un id estable derivado del uuid
        id = (uuid ?: playable ?: name).hashCode(),
        name = name?.trim().orEmpty(),
        icon = favicon?.takeIf { it.isNotBlank() },
        categoryId = categoryIdFor(source),
        type = ContentType.LIVE,
        streamUrl = playable
    )

    private fun Fixed.toContentItem(source: Source) = ContentItem(
        id = url.hashCode(),
        name = name,
        icon = icon,
        categoryId = categoryIdFor(source),
        type = ContentType.LIVE,
        streamUrl = url
    )

    fun cancel() {
        pendingCall?.cancel()
        pendingCall = null
    }
}
