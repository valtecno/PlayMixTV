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
    private const val KEY_CLEARED_AT = "borrado_en"
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
        // progress es transitorio de "Continuar viendo" (ver
        // ContentItem.progress): si se guarda tal cual en Historial, la
        // carátula de "Vistas" queda con una barra de avance congelada en el
        // % de cuando se abrió desde ahí, en vez de mostrarse como Vistas
        // normalmente se ve (sin barra).
        // syncUpdatedAt marca cuándo entró: es lo que permite que "Limpiar
        // historial" en otro equipo (ver clear/mergeFromRemote) sepa si este
        // ítem es de antes o de después de esa limpieza.
        val updated = mutableListOf(item.copy(progress = null, syncUpdatedAt = System.currentTimeMillis()))
        updated.addAll(getAll(context).filter { uniqueKey(it.type, it.id) != key })
        prefs(context).edit()
            .putString(KEY, gson.toJson(updated.take(MAX)))
            .apply()
        DataSync.scheduleBackup(context)
    }

    /** Momento (epoch ms) de la última vez que se vació el historial, o 0 si nunca. */
    fun clearedAt(context: Context): Long = prefs(context).getLong(KEY_CLEARED_AT, 0L)

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY)
            .putLong(KEY_CLEARED_AT, System.currentTimeMillis())
            .apply()
        DataSync.scheduleBackup(context)
    }

    /**
     * Combina lo que bajó de la nube (ver DataSync) con lo que ya hay en este
     * equipo. Sin fecha por ítem local antigua (a diferencia de Continuar
     * viendo), pero desde este cambio cada ítem nuevo sí trae
     * [ContentItem.syncUpdatedAt], así que un "Limpiar historial" hecho en
     * OTRO equipo se puede propagar acá: se descarta cualquier ítem (local o
     * remoto) más viejo que el vaciado más reciente entre los dos equipos.
     * Los ítems de antes de este cambio quedan con syncUpdatedAt=0 y se
     * tratan como "de siempre" mientras nunca haya habido un vaciado -si lo
     * hay, se pierden junto con el resto, igual que en cualquier otro equipo
     * que sí haya sincronizado antes del vaciado.
     */
    fun mergeFromRemote(
        context: Context,
        remoto: List<ContentItem>,
        remoteClearedAt: Long = 0L
    ): List<ContentItem> {
        val local = getAll(context)
        val vaciadoCombinado = maxOf(clearedAt(context), remoteClearedAt)

        val clavesLocales = local.mapTo(HashSet(local.size)) { uniqueKey(it.type, it.id) }
        val extra = remoto.filterNot { uniqueKey(it.type, it.id) in clavesLocales }
        var combinado = (local + extra)

        if (vaciadoCombinado > 0) {
            combinado = combinado.filter { it.syncUpdatedAt > vaciadoCombinado }
        }
        combinado = combinado.sortedByDescending { it.syncUpdatedAt }.take(MAX)

        prefs(context).edit()
            .putString(KEY, gson.toJson(combinado))
            .putLong(KEY_CLEARED_AT, vaciadoCombinado)
            .apply()
        return combinado
    }
}
