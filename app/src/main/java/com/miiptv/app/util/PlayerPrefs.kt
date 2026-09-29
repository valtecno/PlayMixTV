package com.miiptv.app.util

import android.content.Context
import com.miiptv.app.R

/**
 * Ajustes internos del reproductor, editables desde Ajustes del sistema.
 * Se guardan en el dispositivo y los lee PlayerActivity al abrir cada stream.
 */
object PlayerPrefs {

    private const val PREFS = "miiptv_player_prefs"

    // ---- Buffer ----
    const val BUFFER_LOW = 0      // arranca rápido, más sensible a cortes
    const val BUFFER_NORMAL = 1
    const val BUFFER_HIGH = 2     // arranca más lento, aguanta mejor una conexión inestable

    // ---- Relación de aspecto ----
    const val FIT = 0             // ajustar: respeta la proporción (puede dejar bandas negras)
    const val CROP = 1            // rellenar: llena la pantalla recortando los bordes
    const val STRETCH = 2         // estirar: llena la pantalla deformando la imagen

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getBuffer(c: Context) = prefs(c).getInt("buffer", BUFFER_NORMAL)
    fun setBuffer(c: Context, v: Int) = prefs(c).edit().putInt("buffer", v).apply()

    fun getAspect(c: Context) = prefs(c).getInt("aspect", FIT)
    fun setAspect(c: Context, v: Int) = prefs(c).edit().putInt("aspect", v).apply()

    fun getAutoReconnect(c: Context) = prefs(c).getBoolean("auto_reconnect", true)
    fun setAutoReconnect(c: Context, v: Boolean) = prefs(c).edit().putBoolean("auto_reconnect", v).apply()

    fun getBackground(c: Context) = prefs(c).getBoolean("background_playback", true)
    fun setBackground(c: Context, v: Boolean) = prefs(c).edit().putBoolean("background_playback", v).apply()

    fun getAutoPlayNext(c: Context) = prefs(c).getBoolean("autoplay_next", true)
    fun setAutoPlayNext(c: Context, v: Boolean) = prefs(c).edit().putBoolean("autoplay_next", v).apply()

    fun getKeepScreenOn(c: Context) = prefs(c).getBoolean("keep_screen_on", true)
    fun setKeepScreenOn(c: Context, v: Boolean) = prefs(c).edit().putBoolean("keep_screen_on", v).apply()

    // ---- Idioma de audio preferido para Películas/Series (código ISO-639: "spa","eng").
    // null = sin preferencia, se deja la pista que traiga el stream por defecto. ----
    fun getAudioLanguage(c: Context): String? = prefs(c).getString("audio_language", null)
    fun setAudioLanguage(c: Context, v: String?) = prefs(c).edit().putString("audio_language", v).apply()

    fun audioLanguageLabel(c: Context, lang: String?): String = c.getString(
        when (lang) {
            "spa" -> R.string.audio_lang_spanish
            "eng" -> R.string.audio_lang_english
            else -> R.string.audio_lang_none
        }
    )

    // ---- Subtítulos para Películas/Series ----
    const val SUB_OFF = 0       // apagados de entrada
    const val SUB_SPANISH = 1
    const val SUB_ENGLISH = 2
    const val SUB_AUTO = 3      // no fuerza idioma: deja el que traiga el stream por defecto

    fun getSubtitleMode(c: Context) = prefs(c).getInt("subtitle_mode", SUB_OFF)
    fun setSubtitleMode(c: Context, v: Int) = prefs(c).edit().putInt("subtitle_mode", v).apply()

    fun subtitleModeLabel(c: Context, mode: Int): String = c.getString(
        when (mode) {
            SUB_SPANISH -> R.string.subtitle_spanish
            SUB_ENGLISH -> R.string.subtitle_english
            SUB_AUTO -> R.string.subtitle_auto
            else -> R.string.subtitle_off
        }
    )

    // ---- Volumen del reproductor (0-100), independiente del volumen del
    // sistema. Se guarda para que el próximo canal arranque con el mismo
    // nivel en vez de siempre al 100%. ----
    fun getVolume(c: Context) = prefs(c).getInt("player_volume", 100).coerceIn(0, 100)
    fun setVolume(c: Context, v: Int) = prefs(c).edit().putInt("player_volume", v.coerceIn(0, 100)).apply()

    /** Milisegundos de buffer (mínimo, máximo) según el nivel elegido. */
    fun bufferMillis(level: Int): Pair<Int, Int> = when (level) {
        BUFFER_LOW -> 5_000 to 20_000
        BUFFER_HIGH -> 30_000 to 120_000
        else -> 15_000 to 50_000
    }

    fun bufferLabel(c: Context, level: Int): String = c.getString(
        when (level) {
            BUFFER_LOW -> R.string.buffer_low
            BUFFER_HIGH -> R.string.buffer_high
            else -> R.string.buffer_normal
        }
    )

    fun aspectLabel(c: Context, mode: Int): String = c.getString(
        when (mode) {
            CROP -> R.string.aspect_fill
            STRETCH -> R.string.aspect_stretch
            else -> R.string.aspect_fit
        }
    )
}
