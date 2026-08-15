package com.miiptv.app.util

import android.content.Context

/**
 * Recuerda si el **Perfil de niños** está activo, para que se mantenga así
 * aunque se cierre y reabra la app. Salir requiere el PIN de control
 * parental (ver [Parental]); por eso activarlo exige tener un PIN creado.
 */
object KidsMode {
    private const val PREFS = "miiptv_kids"
    private const val KEY_ACTIVE = "active"

    fun isActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACTIVE, false)

    fun setActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_ACTIVE, active).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
