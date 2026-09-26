package com.miiptv.app.util

import android.content.Context

/**
 * Guarda la posición de reproducción de cada episodio para que el usuario
 * pueda continuar donde lo dejó sin tener que recordarlo él mismo.
 *
 * Se guarda la posición en milisegundos cada vez que el reproductor se pausa
 * o cierra. Al volver al episodio, `SeriesDetailActivity` la lee y se la
 * pasa al reproductor para que arranque desde ese punto.
 *
 * La posición se descarta automáticamente cuando el episodio se ve hasta el
 * final (los últimos 3 minutos se consideran "completado") o cuando el usuario
 * lo reproduce desde el principio de forma explícita.
 *
 * La clave identifica el episodio por su URL de reproducción: es única por
 * episodio y no depende de cómo esté organizado el servidor.
 */
object EpisodeProgress {
    private const val PREFS = "miiptv_episode_progress"

    /** Umbral a partir del cual se considera que el episodio está "completado" (ms antes del final). */
    private const val COMPLETADO_UMBRAL_MS = 3 * 60 * 1000L   // 3 minutos

    /**
     * Guarda la posición actual del episodio.
     *
     * @param url      URL de reproducción del episodio (clave única).
     * @param posMs    Posición en milisegundos.
     * @param durMs    Duración total en milisegundos (0 si no se conoce).
     */
    fun save(context: Context, url: String, posMs: Long, durMs: Long) {
        if (url.isBlank() || posMs <= 0) return
        val completado = durMs > 0 && (durMs - posMs) < COMPLETADO_UMBRAL_MS
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (completado) {
            // Episodio terminado: limpiar para que la próxima vez arranque desde el principio
            prefs.edit().remove(url).apply()
        } else {
            prefs.edit().putLong(url, posMs).apply()
        }
    }

    /**
     * Devuelve la posición guardada para este episodio, o 0 si no hay ninguna.
     */
    fun get(context: Context, url: String): Long {
        if (url.isBlank()) return 0L
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(url, 0L)
    }

    /** Elimina el progreso guardado de un episodio (al reproducir desde el principio). */
    fun clear(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(url).apply()
    }
}
