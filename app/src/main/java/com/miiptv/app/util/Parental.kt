package com.miiptv.app.util

import android.content.Context
import java.security.MessageDigest

/**
 * Control parental simple: un PIN de 4+ dígitos protege el acceso a
 * categorías marcadas como bloqueadas, y a la propia pantalla de ajustes
 * de control parental (para que no cualquiera lo desactive).
 */
object Parental {
    private const val PREFS = "miiptv_parental"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_LOCKED_CATEGORIES = "locked_categories"

    private fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun hasPin(context: Context): Boolean =
        prefs(context).getString(KEY_PIN_HASH, null) != null

    fun setPin(context: Context, pin: String) {
        prefs(context).edit().putString(KEY_PIN_HASH, hash(pin)).apply()
    }

    fun removePin(context: Context) {
        prefs(context).edit().remove(KEY_PIN_HASH).apply()
    }

    fun checkPin(context: Context, pin: String): Boolean =
        prefs(context).getString(KEY_PIN_HASH, null) == hash(pin)

    fun lockedCategories(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_LOCKED_CATEGORIES, emptySet()) ?: emptySet()

    fun isCategoryLocked(context: Context, categoryId: String?): Boolean =
        categoryId != null && lockedCategories(context).contains(categoryId)

    fun setCategoryLocked(context: Context, categoryId: String, locked: Boolean) {
        val current = lockedCategories(context).toMutableSet()
        if (locked) current.add(categoryId) else current.remove(categoryId)
        prefs(context).edit().putStringSet(KEY_LOCKED_CATEGORIES, current).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
