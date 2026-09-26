package com.miiptv.app.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.miiptv.app.api.ContentItem
import com.miiptv.app.api.ContentType

/**
 * Historial de lo último reproducido. Guarda los ítems más recientes primero,
 * sin repetidos, con un tope para no crecer indefinidamente.
 *
 * Cada cuenta tiene su propio archivo de historial (igual que Favoritos):
 * así el contenido de Sistema L no se mezcla con el de Sistema XL.
 */
object History {
    private const val LEGACY_PREFS = "miiptv_history"
    private const val LEGACY_MIGRATED_KEY = "legacy_migrated"
    private const val KEY = "items"
    private const val MAX = 60
    private val gson = Gson()

    private fun uniqueKey(type: ContentType, id: Int) = "$type:$id"

    private fun accountKey(context: Context): String {
        val server = com.miiptv.app.api.Session.server(context)
        val user   = com.miiptv.app.api.Session.username(context)
        return "$server|$user".replace(Regex("[^A-Za-z0-9]"), "_").take(80)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("miiptv_history_${accountKey(context)}", Context.MODE_PRIVATE)

    private fun migrateLegacyIfNeeded(context: Context) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (legacy.getBoolean(LEGACY_MIGRATED_KEY, false)) return
        legacy.edit().putBoolean(LEGACY_MIGRATED_KEY, true).apply()
        val json = legacy.getString(KEY, null) ?: return
        val destino = prefs(context)
        if (!destino.contains(KEY)) destino.edit().putString(KEY, json).apply()
    }

    fun getAll(context: Context): List<ContentItem> {
        migrateLegacyIfNeeded(context)
        val json = prefs(context).getString(KEY, null) ?: return emptyList()
        val type = object : TypeToken<List<ContentItem>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Registra una reproducción: la mueve al principio y descarta duplicados. */
    fun add(context: Context, item: ContentItem) {
        migrateLegacyIfNeeded(context)
        val key = uniqueKey(item.type, item.id)
        val updated = mutableListOf(item)
        updated.addAll(getAll(context).filter { uniqueKey(it.type, it.id) != key })
        prefs(context).edit()
            .putString(KEY, gson.toJson(updated.take(MAX)))
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }
}
