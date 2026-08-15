package com.miiptv.app.util

import androidx.media3.exoplayer.ExoPlayer

/**
 * Guarda el reproductor vivo fuera del ciclo de vida de la pantalla, para que
 * el audio siga sonando al salir de la app (sobre todo las radios).
 *
 * La pantalla lo toma prestado mientras está visible; al irse a segundo plano,
 * el servicio [com.miiptv.app.service.PlaybackService] lo mantiene con vida.
 */
object PlaybackHolder {

    var player: ExoPlayer? = null
        private set

    /** URL que está sonando ahora, para poder retomarla sin reiniciar el stream. */
    var currentUrl: String? = null
        private set

    var currentTitle: String = ""

    fun attach(player: ExoPlayer, url: String, title: String) {
        this.player = player
        this.currentUrl = url
        this.currentTitle = title
    }

    /** ¿Ya hay un reproductor vivo con este mismo stream? */
    fun canResume(url: String): Boolean = player != null && currentUrl == url

    val isPlaying: Boolean get() = player?.isPlaying == true

    fun togglePlayPause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
    }

    fun release() {
        player?.release()
        player = null
        currentUrl = null
        currentTitle = ""
    }
}
