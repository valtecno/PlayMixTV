package com.miiptv.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.miiptv.app.R
import com.miiptv.app.api.XtreamStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Un solo lugar para decidir, ante cualquier error de red, si el problema es
 * del EQUIPO del usuario (sin internet) o del ORIGEN (el panel/servidor).
 *
 * Antes cada pantalla reinventaba esto por su cuenta: el reproductor normal
 * lo hacía bien, la multipantalla solo a medias (sin mirar la conexión local),
 * y el resto (Inicio, Login, Detalle de serie) mostraba el nombre técnico de
 * la excepción en inglés, sin decir de qué lado estaba el problema.
 */
object ErrorDiagnosis {

    enum class Causa {
        /** El equipo no tiene internet: wifi caído, avión, datos apagados. */
        SIN_INTERNET,
        /** El panel respondió, pero con una señal de cuenta vencida o tope de conexiones. */
        LIMITE_O_VENCIDA,
        /** El panel no contesta a tiempo (puede ser el servidor, no el usuario). */
        SERVIDOR_LENTO,
        /** El panel respondió con un error (HTTP 4xx/5xx) que no es de límite. */
        SERVIDOR_RECHAZA,
        /** No se pudo determinar con certeza. */
        DESCONOCIDA
    }

    /** true si el dispositivo tiene conectividad de red activa en este momento. */
    fun hayInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true // sin forma de preguntar, no se culpa al usuario sin pruebas
        // activeNetwork y NetworkCapabilities son de API 23.
        if (Build.VERSION.SDK_INT < 23) {
            @Suppress("DEPRECATION")
            return cm.activeNetworkInfo?.isConnected == true
        }
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        // Sin exigir VALIDATED: en VPN o redes locales Android no la marca y
        // se diría "sin internet" aunque el panel respondiera bien.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Para un fallo de Retrofit (onFailure: nunca llegó a haber respuesta). */
    fun causaDeFallo(context: Context, t: Throwable): Causa {
        if (!hayInternet(context)) return Causa.SIN_INTERNET
        return when {
            t is XtreamStream.ShapeException -> Causa.LIMITE_O_VENCIDA
            t is SocketTimeoutException -> Causa.SERVIDOR_LENTO
            t is UnknownHostException -> Causa.SIN_INTERNET // DNS no resuelve: casi siempre red local
            else -> Causa.DESCONOCIDA
        }
    }

    /** Para una respuesta HTTP ya recibida pero no exitosa (sí hubo conexión). */
    fun causaDeHttp(code: Int): Causa = when (code) {
        401, 403, 512 -> Causa.LIMITE_O_VENCIDA
        in 500..599 -> Causa.SERVIDOR_LENTO
        else -> Causa.SERVIDOR_RECHAZA
    }

    /** Para un error de reproducción de Media3 (PlayerActivity/MultiScreenActivity). */
    fun causaDePlayback(context: Context, error: PlaybackException): Causa {
        if (!hayInternet(context)) return Causa.SIN_INTERNET
        val causa = error.cause
        if (causa is HttpDataSource.InvalidResponseCodeException) return causaDeHttp(causa.responseCode)
        val esErrorDeRed = error.errorCode in 2000..2999 // E/S de red de Media3
        return if (esErrorDeRed) Causa.SERVIDOR_LENTO else Causa.DESCONOCIDA
    }

    /** Mensaje en español, listo para mostrar, para cualquier [Causa]. */
    fun mensaje(context: Context, causa: Causa): String = context.getString(
        when (causa) {
            Causa.SIN_INTERNET -> R.string.error_sin_internet
            Causa.LIMITE_O_VENCIDA -> R.string.error_limite_o_vencida
            Causa.SERVIDOR_LENTO -> R.string.error_servidor_lento
            Causa.SERVIDOR_RECHAZA -> R.string.error_servidor_rechaza
            Causa.DESCONOCIDA -> R.string.error_desconocida
        }
    )
}
