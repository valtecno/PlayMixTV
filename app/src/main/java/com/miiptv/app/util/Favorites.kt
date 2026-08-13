package com.miiptv.app.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.miiptv.app.api.ContentItem
import com.miiptv.app.api.ContentType

/**
 * Guarda canales/películas/series marcados como favoritos en el dispositivo.
 * Usamos una key única por item: "TIPO:ID" para poder buscarlo rápido.
 */
object Favorites {
    private const val PREFS = "miiptv_favorites"
    private const val KEY = "items"
    private val gson = Gson()

    private fun uniqueKey(type: ContentType, id: Int) = "$type:$id"

    fun getAll(context: Context): List<ContentItem> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val type = object : TypeToken<List<ContentItem>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isFavorite(context: Context, item: ContentItem): Boolean =
        getAll(context).any { uniqueKey(it.type, it.id) == uniqueKey(item.type, item.id) }

    fun toggle(context: Context, item: ContentItem): Boolean {
        val current = getAll(context).toMutableList()
        val key = uniqueKey(item.type, item.id)
        val existing = current.find { uniqueKey(it.type, it.id) == key }
        val nowFavorite: Boolean
        if (existing != null) {
            current.remove(existing)
            nowFavorite = false
        } else {
            current.add(item)
            nowFavorite = true
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, gson.toJson(current))
            .apply()
        return nowFavorite
    }
}
