package com.miiptv.app.util

import android.app.ActivityManager
import android.content.Context

/**
 * Detecta equipos de pocos recursos (decodificadores genéricos, TV box viejos,
 * teléfonos de 1 GB) para que la app gaste menos memoria y deje de hacer
 * animaciones que ahí cuestan fluidez. En un equipo normal no cambia nada.
 */
object DevicePerf {

    private const val MB = 1024L * 1024L

    /** Lo que detectó el equipo por su cuenta (se calcula una vez). */
    @Volatile private var detectado: Boolean? = null

    private const val PREFS = "miiptv_perf"
    private const val KEY_MANUAL = "modo_equipo_debil"   // 1 = activado, 0 = desactivado, ausente = automático

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** true si la detección automática considera débil a este equipo (ignora lo elegido a mano). */
    fun detectadoDebil(context: Context): Boolean =
        detectado ?: calcular(context.applicationContext).also { detectado = it }

    /** Lo elegido a mano en Ajustes: true/false, o null si se deja en automático. */
    fun manual(context: Context): Boolean? {
        val p = prefs(context)
        return if (p.contains(KEY_MANUAL)) p.getInt(KEY_MANUAL, 0) == 1 else null
    }

    fun setManual(context: Context, activado: Boolean) {
        prefs(context).edit().putInt(KEY_MANUAL, if (activado) 1 else 0).apply()
        efectivo = null
    }

    /** Resultado final ya resuelto: esta consulta se hace en cada fila de las listas. */
    @Volatile private var efectivo: Boolean? = null

    /**
     * true = modo equipo débil activo: poca RAM, pocos núcleos o marcado por
     * Android como "low RAM", salvo que el usuario lo haya activado o
     * desactivado a mano en Ajustes (eso manda siempre).
     */
    fun esGamaBaja(context: Context): Boolean =
        efectivo ?: (manual(context) ?: detectadoDebil(context)).also { efectivo = it }

    private fun calcular(c: Context): Boolean {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return es(
            lowRamFlag = am.isLowRamDevice,
            totalMemBytes = info.totalMem,
            memoryClassMb = am.memoryClass,
            nucleos = Runtime.getRuntime().availableProcessors()
        )
    }

    /** Regla pura (sin Android), para poder probarla. */
    fun es(lowRamFlag: Boolean, totalMemBytes: Long, memoryClassMb: Int, nucleos: Int): Boolean =
        lowRamFlag ||
            (totalMemBytes in 1 until 1_600 * MB) ||   // hasta ~1,5 GB de RAM
            (memoryClassMb in 1..80) ||
            nucleos <= 2

    /**
     * Tope de bytes del buffer de video. El de ExoPlayer por defecto puede
     * reservar más de 100 MB por reproductor; acá se acota según el equipo.
     */
    fun bufferMaxBytes(context: Context): Int =
        if (esGamaBaja(context)) (24 * MB).toInt() else (64 * MB).toInt()

    /** Bytes de la caché de imágenes ya decodificadas. */
    fun memoriaImagenes(maxHeapBytes: Long, gamaBaja: Boolean): Int {
        val tope = if (gamaBaja) 24 * MB else 96 * MB
        val propuesta = if (gamaBaja) maxHeapBytes / 10 else maxHeapBytes / 5
        return propuesta.coerceAtMost(tope).coerceAtLeast(8 * MB).toInt()
    }
}
