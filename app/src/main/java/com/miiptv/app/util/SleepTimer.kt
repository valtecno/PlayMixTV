package com.miiptv.app.util

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.miiptv.app.R
import com.miiptv.app.service.PlaybackService

/**
 * Temporizador para dormir de toda la app. Vive fuera del reproductor para
 * que siga corriendo aunque se salga de la pantalla de video, y al cumplirse
 * cierra la aplicación completa: corta la reproducción, el servicio de audio
 * y todas las pantallas abiertas.
 */
object SleepTimer {
    /** Opciones del diálogo, en minutos (0 = desactivar). */
    val OPCIONES = intArrayOf(0, 15, 30, 60, 120)

    private val handler = Handler(Looper.getMainLooper())
    private val pantallas = mutableListOf<Activity>()

    /** Minutos elegidos; 0 si no hay temporizador activo. */
    var minutos = 0
        private set

    val activo get() = minutos > 0

    fun registrar(a: Activity) { pantallas.add(a) }
    fun olvidar(a: Activity) { pantallas.remove(a) }

    fun etiqueta(context: Context, m: Int): String = when (m) {
        0 -> context.getString(R.string.sleep_off)
        60 -> context.getString(R.string.sleep_hour)
        120 -> context.getString(R.string.sleep_hours)
        else -> context.getString(R.string.sleep_minutes, m)
    }

    fun programar(context: Context, m: Int) {
        handler.removeCallbacksAndMessages(null)
        minutos = m
        if (m <= 0) return
        val app = context.applicationContext
        handler.postDelayed({ cerrarApp(app) }, m * 60_000L)
    }

    private fun cerrarApp(app: Context) {
        minutos = 0
        Toast.makeText(app, R.string.sleep_fired, Toast.LENGTH_LONG).show()
        // Las pantallas guardan el avance y sueltan la conexión en onPause/onDestroy
        pantallas.toList().forEach { runCatching { it.finishAndRemoveTask() } }
        runCatching { PlaybackService.stop(app) }
        runCatching { PlaybackHolder.release() }
    }
}
