package com.miiptv.app.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.miiptv.app.api.ContentItem
import com.miiptv.app.api.ContentType

/**
 * Historial de lo último reproducido. Guarda los items más recientes primero,
 * sin repetidos, con un tope para no crecer indefinidamente.
 */
object History {
    private const val PREFS = "miiptv_history"
    private const val KEY = "items"
    private const val MAX = 60
    private val gson = Gson()

    private fun uniqueKey(type: ContentType, id: Int) = "$type:$id"

    fun getAll(context: Context): List<ContentItem> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        val type = object : TypeToken<List<ContentItem>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Registra una reproducción: la mueve al principio y descarta duplicados. */
    fun add(context: Context, item: ContentItem) {
        val key = uniqueKey(item.type, item.id)
        val updated = mutableListOf(item)
        updated.addAll(getAll(context).filter { uniqueKey(it.type, it.id) != key })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, gson.toJson(updated.take(MAX)))
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
