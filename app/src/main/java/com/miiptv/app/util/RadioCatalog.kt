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
 */
object RadioCatalog {

    /** Países ofrecidos, en el orden en que se muestran. */
    data class Country(val flag: String, val name: String, val code: String, val genres: String)

    val countries = listOf(
        Country("🇺🇸", "Estados Unidos", "US", "Pop, rock, country, hip-hop, R&B, electrónica"),
        Country("🇲🇽", "México", "MX", "Regional mexicano, pop latino, banda, norteño"),
        Country("🇧🇷", "Brasil", "BR", "Sertanejo, pop, funk brasileño, MPB"),
        Country("🇨🇴", "Colombia", "CO", "Reggaetón, vallenato, pop latino, salsa"),
        Country("🇦🇷", "Argentina", "AR", "Rock nacional, pop, urbano, electrónica"),
        Country("🇨🇱", "Chile", "CL", "Pop, rock, urbano, música latina"),
        Country("🇨🇦", "Canadá", "CA", "Pop, rock, country, música alternativa"),
        Country("🇵🇪", "Perú", "PE", "Salsa, cumbia, pop, urbano"),
        Country("🇻🇪", "Venezuela", "VE", "Salsa, merengue, pop latino, urbano"),
        Country("🇪🇨", "Ecuador", "EC", "Pop latino, urbano, salsa, reggaetón"),
        Country("🇩🇴", "Rep. Dominicana", "DO", "Bachata, merengue, urbano"),
        Country("🇵🇷", "Puerto Rico", "PR", "Reguetón, trap latino, salsa, pop")
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

    fun categoryIdFor(country: Country) = "radio_${country.code}"

    /**
     * Carga las emisoras de un país. Si ya se pidieron antes en esta sesión,
     * responde al instante desde la caché.
     */
    fun load(
        context: Context,
        country: Country,
        onResult: (List<ContentItem>, error: String?) -> Unit
    ) {
        cache[country.code]?.let {
            onResult(it, null)
            return
        }
        pendingCall?.cancel()
        request(country, mirrorIndex = 0, onResult = onResult)
    }

    private fun request(
        country: Country,
        mirrorIndex: Int,
        onResult: (List<ContentItem>, String?) -> Unit
    ) {
        if (mirrorIndex >= mirrors.size) {
            onResult(emptyList(), "No se pudo conectar al directorio de radios")
            return
        }

        val call = apiFor(mirrors[mirrorIndex]).byCountry(country.code)
        pendingCall = call
        call.enqueue(object : Callback<List<RadioStation>> {
            override fun onResponse(
                call: Call<List<RadioStation>>,
                response: Response<List<RadioStation>>
            ) {
                val items = response.body().orEmpty()
                    .filter { !it.name.isNullOrBlank() && !it.playable.isNullOrBlank() }
                    .distinctBy { it.playable }
                    .map { it.toContentItem(country) }

                if (items.isEmpty() && mirrorIndex + 1 < mirrors.size) {
                    request(country, mirrorIndex + 1, onResult)
                } else {
                    cache[country.code] = items
                    onResult(items, null)
                }
            }

            override fun onFailure(call: Call<List<RadioStation>>, t: Throwable) {
                if (call.isCanceled) return
                // Probamos el siguiente espejo antes de dar error
                request(country, mirrorIndex + 1, onResult)
            }
        })
    }

    private fun RadioStation.toContentItem(country: Country) = ContentItem(
        // El id de Xtream es numérico; para las radios usamos un id estable derivado del uuid
        id = (uuid ?: playable ?: name).hashCode(),
        name = name?.trim().orEmpty(),
        icon = favicon?.takeIf { it.isNotBlank() },
        categoryId = categoryIdFor(country),
        type = ContentType.LIVE,
        streamUrl = playable
    )

    fun cancel() {
        pendingCall?.cancel()
        pendingCall = null
    }
}
