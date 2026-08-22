package com.miiptv.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Temperatura local del usuario, para la pantalla ampliada de radios.
 *
 * El aparato es una tele/caja sin GPS y sin permiso de ubicación pedido, así
 * que en vez de LocationManager se ubica por IP (ipwho.is, gratis y sin
 * clave) y con esas coordenadas se consulta el clima actual en Open-Meteo
 * (también gratis y sin clave). Se guarda en caché un rato para no golpear
 * ambos servicios cada vez que se abre una radio.
 */
object WeatherService {

    private const val PREFS = "miiptv_weather"
    private const val KEY_TEMP = "temp_c"
    private const val KEY_TIME = "fetched_at"

    /** No tiene sentido resolver esto de nuevo antes de que pase este rato. */
    private const val CACHE_MS = 20 * 60 * 1000L

    private val ui = Handler(Looper.getMainLooper())

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Devuelve la temperatura en grados Celsius, redondeada, o null si no se
     * pudo resolver (sin datos, servicio caído, etc). [onResult] siempre se
     * llama en el hilo principal.
     */
    fun fetch(context: Context, onResult: (Int?) -> Unit) {
        val ctx = context.applicationContext
        val p = prefs(ctx)
        val edad = System.currentTimeMillis() - p.getLong(KEY_TIME, 0L)
        if (edad < CACHE_MS && p.contains(KEY_TEMP)) {
            onResult(p.getInt(KEY_TEMP, 0))
            return
        }

        Thread {
            val temp = resolver()
            if (temp != null) {
                p.edit()
                    .putInt(KEY_TEMP, temp)
                    .putLong(KEY_TIME, System.currentTimeMillis())
                    .apply()
            }
            ui.post { onResult(temp) }
        }.start()
    }

    private fun resolver(): Int? {
        val (lat, lon) = ubicarPorIp() ?: return null
        return climaActual(lat, lon)
    }

    private fun ubicarPorIp(): Pair<Double, Double>? = try {
        val peticion = Request.Builder().url("https://ipwho.is/").build()
        Session.httpClient.newCall(peticion).execute().use { r ->
            if (!r.isSuccessful) return null
            val json = JSONObject(r.body?.string().orEmpty())
            if (!json.optBoolean("success", true)) return null
            val lat = json.optDouble("latitude", Double.NaN)
            val lon = json.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) null else lat to lon
        }
    } catch (e: Exception) {
        null
    }

    private fun climaActual(lat: Double, lon: Double): Int? = try {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon&current_weather=true"
        val peticion = Request.Builder().url(url).build()
        Session.httpClient.newCall(peticion).execute().use { r ->
            if (!r.isSuccessful) return null
            val json = JSONObject(r.body?.string().orEmpty())
            val actual = json.optJSONObject("current_weather") ?: return null
            if (!actual.has("temperature")) return null
            actual.getDouble("temperature").roundToInt()
        }
    } catch (e: Exception) {
        null
    }
}
