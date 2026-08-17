package com.miiptv.app.util

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
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
     * Sugerencia inicial: es lo que vale **antes** de que el usuario elija, así
     * que de esto depende que el control remoto funcione ya en el primer
     * arranque (ver [RemoteControl]).
     *
     * Se miran tres señales, no una:
     *
     *  1. El aparato se declara como televisor (`UI_MODE_TYPE_TELEVISION`).
     *  2. Trae la interfaz de Android TV (`FEATURE_LEANBACK`).
     *  3. No tiene pantalla táctil. Esta es la que salva los casos raros: hay
     *     decos y mini-PC con Android que no declaran ninguna de las dos
     *     anteriores, pero que solo se pueden manejar con el remoto. Antes esos
     *     aparatos arrancaban en modo móvil.
     *
     * Sigue siendo solo una sugerencia: lo que el usuario elija manda y queda
     * guardado.
     */
    fun suggest(c: Context): String {
        val uiMode = c.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        val esTelevisor = uiMode == Configuration.UI_MODE_TYPE_TELEVISION
        val pm = c.packageManager
        val tieneLeanback = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val sinTactil = !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        return if (esTelevisor || tieneLeanback || sinTactil) TV else MOBILE
    }

    /** Atajo legible: lo contrario de [isMobile]. */
    fun isTv(c: Context): Boolean = get(c) == TV

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
