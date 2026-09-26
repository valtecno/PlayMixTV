package com.miiptv.app.util

import android.content.Context

/**
 * Recuerda si el **Perfil de niños** está activo, para que se mantenga así
 * aunque se cierre y reabra la app. Salir requiere el PIN de control
 * parental (ver [Parental]); por eso activarlo exige tener un PIN creado.
 *
 * También recuerda el último tramo de edad elegido ([KidsFilter.AgeTier]):
 * el selector se muestra cada vez que se activa el perfil (ver
 * MainActivity.pickAgeTier), pero arranca marcado en lo último que se
 * usó, para no obligar a elegir de nuevo si siempre es el mismo chico.
 */
object KidsMode {
    private const val PREFS = "miiptv_kids"
    private const val KEY_ACTIVE = "active"
    private const val KEY_AGE_TIER = "age_tier"

    fun isActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACTIVE, false)

    fun setActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_ACTIVE, active).apply()
    }

    /** Tramo de edad activo ahora mismo. Sin uno guardado, el de siempre: hasta 10 años. */
    fun getAgeTier(context: Context): KidsFilter.AgeTier {
        val guardado = prefs(context).getString(KEY_AGE_TIER, null)
        return KidsFilter.AgeTier.values().firstOrNull { it.name == guardado } ?: KidsFilter.AgeTier.DIEZ
    }

    fun setAgeTier(context: Context, tier: KidsFilter.AgeTier) {
        prefs(context).edit().putString(KEY_AGE_TIER, tier.name).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
