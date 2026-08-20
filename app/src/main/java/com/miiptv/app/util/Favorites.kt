package com.miiptv.app.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.miiptv.app.api.ContentItem
import com.miiptv.app.api.ContentType

/**
 * Guarda canales/películas/series marcados como favoritos en el dispositivo.
 * Usamos una key única por item: "TIPO:ID" para poder buscarlo rápido.
 *
 * ---------------------------------------------------------------------------
 * POR QUÉ HAY UNA CACHÉ EN MEMORIA
 *
 * `isFavorite` se llama una vez por fila desde `ContentAdapter.onBindViewHolder`.
 * La versión anterior, en CADA una de esas llamadas:
 *
 *   1. abría SharedPreferences,
 *   2. leía el JSON entero de favoritos,
 *   3. lo parseaba con Gson a una List<ContentItem>,
 *   4. y la recorría linealmente buscando una coincidencia.
 *
 * En un scroll rápido eso son unas diez deserializaciones completas por frame.
 * En un teléfono no se nota; en un deco de Android TV de gama baja es
 * exactamente el tirón que aparece al recorrer la grilla con el control remoto.
 *
 * Ahora el JSON se lee y se parsea UNA vez, y se mantiene además un índice de
 * claves (`Set<String>`) para que `isFavorite` sea O(1) sin tocar ni disco ni
 * Gson. La caché se rehace sola cuando cambia algo.
 *
 * NOTA SOBRE LA CLAVE: el formato "TIPO:ID" no se toca a propósito. Es lo que
 * ya está escrito en los dispositivos de los usuarios; cambiarlo les borraría
 * los favoritos al actualizar.
 * ---------------------------------------------------------------------------
 */
object Favorites {
    private const val PREFS = "miiptv_favorites"
    private const val KEY = "items"
    private val gson = Gson()

    /**
     * Copia en memoria de lo guardado. `null` significa "todavía no se leyó" o
     * "quedó invalidada", no "no hay favoritos" — un usuario sin favoritos tiene
     * una lista vacía, no null.
     */
    @Volatile private var cachedItems: List<ContentItem>? = null

    /** Índice de claves de [cachedItems]. Se rehace junto con ella, nunca aparte. */
    @Volatile private var cachedKeys: Set<String> = emptySet()

    // ---------------- Lógica pura (testeable sin Context) ----------------

    fun uniqueKey(type: ContentType, id: Int): String = "$type:$id"

    fun uniqueKey(item: ContentItem): String = uniqueKey(item.type, item.id)

    /** Índice de búsqueda de una lista de favoritos. */
    fun keysOf(items: List<ContentItem>): Set<String> =
        items.mapTo(HashSet(items.size)) { uniqueKey(it) }

    // ---------------- Lectura ----------------

    fun getAll(context: Context): List<ContentItem> = load(context)

    fun isFavorite(context: Context, item: ContentItem): Boolean {
        load(context)                       // asegura que el índice esté armado
        return cachedKeys.contains(uniqueKey(item))
    }

    private fun load(context: Context): List<ContentItem> {
        cachedItems?.let { return it }
        synchronized(this) {
            cachedItems?.let { return it }
            val items = readFromDisk(context)
            cachedItems = items
            cachedKeys = keysOf(items)
            return items
        }
    }

    private fun readFromDisk(context: Context): List<ContentItem> {
        val json = prefs(context).getString(KEY, null) ?: return emptyList()
        val type = object : TypeToken<List<ContentItem>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            // JSON corrupto (actualización a medias, disco lleno). Mejor perder
            // los favoritos que dejar la app sin poder dibujar una sola lista.
            emptyList()
        }
    }

    // ---------------- Escritura ----------------

    /** @return true si el ítem quedó marcado como favorito. */
    fun toggle(context: Context, item: ContentItem): Boolean {
        val key = uniqueKey(item)
        val current = load(context)
        val yaEstaba = cachedKeys.contains(key)

        val updated = if (yaEstaba) {
            current.filterNot { uniqueKey(it) == key }
        } else {
            current + item
        }

        save(context, updated)
        return !yaEstaba
    }

    /**
     * Escribe y deja la caché coherente en el mismo paso.
     *
     * Importa que sea acá y no en el llamador: si la caché se actualizara por
     * separado quedaría la puerta abierta a que alguien guarde sin refrescarla,
     * y el síntoma sería una estrella que no cambia hasta reiniciar la app.
     */
    private fun save(context: Context, items: List<ContentItem>) {
        synchronized(this) {
            cachedItems = items
            cachedKeys = keysOf(items)
        }
        prefs(context).edit().putString(KEY, gson.toJson(items)).apply()
    }

    /**
     * Obliga a releer del disco en la próxima consulta.
     *
     * Hace falta si algo cambia las preferencias por fuera de esta clase (por
     * ejemplo una restauración de copia de seguridad o un borrado de datos).
     */
    fun invalidate() {
        synchronized(this) {
            cachedItems = null
            cachedKeys = emptySet()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
