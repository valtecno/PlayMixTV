package com.miiptv.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.miiptv.app.api.Session
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Temperatura local del usuario, para la pantalla ampliada de radios.
 *
 * El aparato es una tele/caja sin GPS y sin permiso de ubicación pedido, así
 * que en vez de LocationManager se ubica por IP y con esas coordenadas se
 * consulta el clima actual en Open-Meteo (gratis y sin clave).
 *
 * La primera versión usaba un solo servicio de ubicación por IP (ipwho.is) y
 * fallaba en silencio si no respondía -- que es justo lo que pasó: el reloj
 * se veía pero la temperatura nunca aparecía, sin ningún aviso de qué había
 * fallado. Muchos aparatos de IPTV navegan detrás de un DNS con bloqueo de
 * rastreadores (AdGuard, NextDNS, Pi-hole en el router) que corta justamente
 * este tipo de dominios de geolocalización, aunque el resto de internet
 * funcione normal. Por eso ahora se prueban varios proveedores en cadena -- si
 * el DNS bloquea uno, probablemente no bloquee todos -- y cada intento queda
 * en Logcat (tag WeatherService) para poder ver ahí cuál responde y cuál no.
 */
object WeatherService {

    private const val TAG = "WeatherService"

    private const val PREFS = "miiptv_weather"
    private const val KEY_TEMP = "temp_c"
    private const val KEY_TIME = "fetched_at"

    /** No tiene sentido resolver esto de nuevo antes de que pase este rato. */
    private const val CACHE_MS = 20 * 60 * 1000L

    private val ui = Handler(Looper.getMainLooper())

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Devuelve la temperatura en grados Celsius, redondeada, o null si ningún
     * proveedor respondió (revisar Logcat, tag "WeatherService", para ver por
     * qué). [onResult] siempre se llama en el hilo principal.
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
        val ubicacion = ubicarPorIp()
        if (ubicacion == null) {
            Log.w(TAG, "Ningún proveedor de ubicación por IP respondió; no se muestra temperatura")
            return null
        }
        val (lat, lon) = ubicacion
        val temp = climaActual(lat, lon)
        if (temp == null) Log.w(TAG, "Open-Meteo no devolvió temperatura para ($lat, $lon)")
        return temp
    }

    /** Prueba cada proveedor en orden y se queda con el primero que responda. */
    private fun ubicarPorIp(): Pair<Double, Double>? {
        val proveedores = listOf(
            ::ubicarConIpwhoIs,
            ::ubicarConGeoJs,
            ::ubicarConIpapiCo
        )
        for (proveedor in proveedores) {
            val resultado = proveedor()
            if (resultado != null) return resultado
        }
        return null
    }

    private fun pedir(url: String): JSONObject? = try {
        val peticion = Request.Builder().url(url).build()
        Session.httpClient.newCall(peticion).execute().use { r ->
            if (!r.isSuccessful) {
                Log.w(TAG, "$url respondió HTTP ${r.code}")
                return null
            }
            JSONObject(r.body?.string().orEmpty())
        }
    } catch (e: Exception) {
        Log.w(TAG, "$url falló: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    private fun coords(lat: Double, lon: Double): Pair<Double, Double>? =
        if (lat.isNaN() || lon.isNaN()) null else lat to lon

    private fun ubicarConIpwhoIs(): Pair<Double, Double>? {
        val json = pedir("https://ipwho.is/") ?: return null
        if (!json.optBoolean("success", true)) return null
        return coords(
            json.optDouble("latitude", Double.NaN),
            json.optDouble("longitude", Double.NaN)
        )
    }

    private fun ubicarConGeoJs(): Pair<Double, Double>? {
        // Reemplaza al proveedor anterior (ip-api.com), que solo daba HTTP
        // en el plan gratis. GeoJS es HTTPS y gratis sin clave, así que ya
        // no hace falta la excepción de tráfico en claro para este proveedor.
        val json = pedir("https://get.geojs.io/v1/ip/geo.json") ?: return null
        return coords(
            json.optString("latitude").toDoubleOrNull() ?: Double.NaN,
            json.optString("longitude").toDoubleOrNull() ?: Double.NaN
        )
    }

    private fun ubicarConIpapiCo(): Pair<Double, Double>? {
        val json = pedir("https://ipapi.co/json/") ?: return null
        if (json.has("error")) return null
        return coords(
            json.optDouble("latitude", Double.NaN),
            json.optDouble("longitude", Double.NaN)
        )
    }

    private fun climaActual(lat: Double, lon: Double): Int? {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon&current_weather=true"
        val json = pedir(url) ?: return null
        val actual = json.optJSONObject("current_weather") ?: return null
        if (!actual.has("temperature")) return null
        return actual.getDouble("temperature").roundToInt()
    }
}
