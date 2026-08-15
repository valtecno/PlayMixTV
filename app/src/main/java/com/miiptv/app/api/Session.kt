package com.miiptv.app.api

import android.content.Context
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Guarda los datos de conexión del usuario (servidor, usuario, contraseña)
 * y construye el cliente Retrofit y las URLs de reproducción.
 */
object Session {
    private const val PREFS = "miiptv_prefs"

    /**
     * Agente que la app declara ante los servidores, tanto en la API como al
     * reproducir. Algunos paneles filtran por este valor.
     */
    const val USER_AGENT = "PlayMix TV - VIP"

    /** Cliente HTTP compartido, con el agente propio en todas las peticiones. */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    fun save(context: Context, server: String, username: String, password: String) {
        val clean = server.trim().removeSuffix("/")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("server", clean)
            .putString("username", username.trim())
            .putString("password", password.trim())
            .apply()
    }

    fun server(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("server", "") ?: ""

    fun username(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("username", "") ?: ""

    fun password(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("password", "") ?: ""

    fun isLoggedIn(context: Context): Boolean =
        server(context).isNotBlank() && username(context).isNotBlank()

    fun logout(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun api(context: Context): XtreamApi {
        val baseUrl = server(context) + "/"
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(XtreamApi::class.java)
    }

    /** URL directa del stream en vivo, formato estándar Xtream Codes. */
    fun liveStreamUrl(context: Context, streamId: Int, extension: String = "m3u8"): String {
        return "${server(context)}/live/${username(context)}/${password(context)}/$streamId.$extension"
    }

    /** URL directa de una película (VOD). */
    fun vodStreamUrl(context: Context, streamId: Int, extension: String = "mp4"): String {
        return "${server(context)}/movie/${username(context)}/${password(context)}/$streamId.$extension"
    }

    /** URL directa de un episodio de serie. */
    fun seriesEpisodeUrl(context: Context, episodeId: String, extension: String = "mp4"): String {
        return "${server(context)}/series/${username(context)}/${password(context)}/$episodeId.$extension"
    }
}
