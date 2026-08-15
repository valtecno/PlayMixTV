package com.miiptv.app.util

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration

/**
 * Modo de pantalla elegido por el usuario la primera vez que abre la app.
 *
 * No se detecta automáticamente porque el mismo APK corre en celular, tablet y
 * Android TV, y la diferencia importante no es el tamaño sino cómo se maneja:
 * con el dedo (vertical, una cosa debajo de la otra) o con control remoto
 * (horizontal, aprovechando el ancho).
 */
object DeviceMode {

    private const val PREFS = "miiptv_device"
    private const val KEY = "mode"

    const val MOBILE = "mobile"
    const val TV = "tv"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** ¿Ya eligió? Si no, hay que mostrarle la pantalla de selección. */
    fun isChosen(c: Context): Boolean = prefs(c).getString(KEY, null) != null

    fun get(c: Context): String = prefs(c).getString(KEY, null) ?: suggest(c)

    fun set(c: Context, mode: String) {
        prefs(c).edit().putString(KEY, mode).apply()
    }

    fun isMobile(c: Context): Boolean = get(c) == MOBILE

    /**
     * Sugerencia inicial, solo para preseleccionar la opción más probable:
     * si el aparato se declara como televisor, TV; si no, móvil.
     */
    fun suggest(c: Context): String {
        val uiMode = c.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) TV else MOBILE
    }

    fun label(c: Context, mode: String): String =
        if (mode == TV) c.getString(com.miiptv.app.R.string.mode_tv)
        else c.getString(com.miiptv.app.R.string.mode_mobile)

    /**
     * Bloquea la pantalla en vertical cuando el modo es móvil (en TV no hace nada:
     * el televisor ya es horizontal por naturaleza). Se usa en todas las pantallas
     * salvo el reproductor, que es la única que debe poder pasar a horizontal
     * (y lo hace automáticamente al reproducir, ver PlayerActivity).
     */
    fun lockPortraitIfMobile(activity: Activity) {
        if (isMobile(activity)) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}
