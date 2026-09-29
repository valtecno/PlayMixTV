package com.miiptv.app.util

import android.content.Context

/**
 * Últimas búsquedas escritas por el usuario, para ofrecerlas como atajo
 * en la pantalla de búsqueda.
 */
object RecentSearches {
    private const val PREFS = "miiptv_recent_searches"
    private const val KEY = "queries"
    private const val MAX = 8
    private const val SEP = "\u001F" // separador que no puede aparecer en un texto escrito

    fun getAll(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "")
            .orEmpty()
            .split(SEP)
            .filter { it.isNotBlank() }

    fun add(context: Context, query: String) {
        val q = query.trim()
        if (q.length < 2) return
        val updated = (listOf(q) + getAll(context).filter { !it.equals(q, ignoreCase = true) }).take(MAX)
        save(context, updated)
    }

    fun remove(context: Context, query: String) {
        save(context, getAll(context).filter { it != query })
    }

    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, list: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, list.joinToString(SEP))
            .apply()
    }
}
