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

    @Volatile private var cache: Boolean? = null

    /** true = equipo débil: poca RAM, pocos núcleos o marcado por Android como "low RAM". */
    fun esGamaBaja(context: Context): Boolean =
        cache ?: calcular(context.applicationContext).also { cache = it }

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
