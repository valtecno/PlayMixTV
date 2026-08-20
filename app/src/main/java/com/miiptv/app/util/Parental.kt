package com.miiptv.app.util

import android.content.Context
import java.security.MessageDigest

/**
 * Control parental simple: un PIN de 4+ dígitos protege el acceso a
 * categorías marcadas como bloqueadas, y a la propia pantalla de ajustes
 * de control parental (para que no cualquiera lo desactive).
 *
 * ---------------------------------------------------------------------------
 * POR QUÉ HAY UNA CACHÉ DEL SET BLOQUEADO
 *
 * Igual que en [Favorites]: `isCategoryLocked` se llama una vez por fila en
 * `ContentAdapter.onBindViewHolder`, y también dentro de `Catalog.newest`, que
 * recorre el catálogo entero (decenas de miles de ítems en el Sistema XL).
 * Cada llamada abría SharedPreferences y pedía un `getStringSet`.
 *
 * Es más barato que el Gson de favoritos, pero se repite muchísimas más veces:
 * armar el Inicio significaba una consulta a preferencias por película y por
 * serie del panel. Ahora el set se lee una vez y queda en memoria.
 *
 * El PIN NO se cachea a propósito: se consulta pocas veces (al abrir algo
 * bloqueado) y no hay ninguna razón para tener su hash dando vueltas en memoria
 * más tiempo del necesario.
 * ---------------------------------------------------------------------------
 */
object Parental {
    private const val PREFS = "miiptv_parental"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_LOCKED_CATEGORIES = "locked_categories"

    /** `null` = todavía no se leyó del disco (distinto de "no hay ninguna bloqueada"). */
    @Volatile private var cachedLocked: Set<String>? = null

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

    fun lockedCategories(context: Context): Set<String> {
        cachedLocked?.let { return it }
        synchronized(this) {
            cachedLocked?.let { return it }
            /*
             * Se copia el set que devuelve getStringSet en vez de usarlo tal
             * cual. Android documenta que ese set no se debe conservar ni
             * modificar: la instancia es la interna de SharedPreferences y su
             * contenido puede cambiar bajo los pies. Guardar la referencia
             * directamente sería justo lo que no hay que hacer con una caché.
             */
            val leido = prefs(context).getStringSet(KEY_LOCKED_CATEGORIES, null)?.toSet() ?: emptySet()
            cachedLocked = leido
            return leido
        }
    }

    fun isCategoryLocked(context: Context, categoryId: String?): Boolean =
        categoryId != null && lockedCategories(context).contains(categoryId)

    fun setCategoryLocked(context: Context, categoryId: String, locked: Boolean) {
        val current = lockedCategories(context).toMutableSet()
        if (locked) current.add(categoryId) else current.remove(categoryId)
        val nuevo = current.toSet()
        synchronized(this) { cachedLocked = nuevo }
        prefs(context).edit().putStringSet(KEY_LOCKED_CATEGORIES, nuevo).apply()
    }

    /** Obliga a releer del disco (restauración de copia de seguridad, borrado de datos). */
    fun invalidate() {
        synchronized(this) { cachedLocked = null }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
