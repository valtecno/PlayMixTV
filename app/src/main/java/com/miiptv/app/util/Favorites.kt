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
 * POR QUÉ AHORA HAY UN ARCHIVO POR CUENTA
 *
 * Antes todo vivía en un único archivo ("miiptv_favorites"), sin importar con
 * qué cuenta se había marcado cada ítem. Con Sistema L y Sistema XL usando la
 * misma app (ver Accounts.kt), eso mezclaba los favoritos de los dos: la
 * pantalla de Favoritos mostraba contenido que en ese sistema ni siquiera
 * existe.
 *
 * Ahora cada cuenta (servidor + usuario) tiene su propio archivo, elegido en
 * [prefs] según la sesión activa en ese momento. La caché en memoria guarda
 * además de qué cuenta es (`cachedAccountKey`): si cambia la cuenta activa
 * (Cambiar de cuenta, o cerrar sesión y entrar con otra), la próxima lectura
 * detecta el cambio y descarta la caché vieja sola -- no hace falta invalidar
 * a mano desde ningún otro lado de la app.
 *
 * MIGRACIÓN: los favoritos que ya estaban guardados en el archivo viejo no
 * traen ninguna marca de a qué cuenta pertenecían -- esa relación nunca se
 * guardó. Como mejor esfuerzo, se migran UNA sola vez a la cuenta que esté
 * activa la primera vez que se abre la app actualizada (ver
 * `migrateLegacyIfNeeded`), y el archivo viejo queda marcado como consumido
 * para no repetirlos en otra cuenta después. Quien tenga favoritos mezclados
 * de ambos sistemas va a tener que volver a marcar en el otro los que falten.
 * ---------------------------------------------------------------------------
 */
object Favorites {
    /** Archivo de antes de separar por cuenta. Solo se lee una vez, para migrar. */
    private const val LEGACY_PREFS = "miiptv_favorites"
    private const val LEGACY_MIGRATED_KEY = "legacy_migrated"
    private const val KEY = "items"
    private const val KEY_TOMBSTONES = "borrados"
    /** Una marca de borrado más vieja que esto ya no aporta nada: se descarta. */
    private const val TOMBSTONE_MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000
    private val gson = Gson()

    /**
     * Copia en memoria de lo guardado. `null` significa "todavía no se leyó" o
     * "quedó invalidada", no "no hay favoritos" — un usuario sin favoritos tiene
     * una lista vacía, no null.
     */
    @Volatile private var cachedItems: List<ContentItem>? = null

    /** Índice de claves de [cachedItems]. Se rehace junto con ella, nunca aparte. */
    @Volatile private var cachedKeys: Set<String> = emptySet()

    /** De qué cuenta es [cachedItems]. Si no coincide con la activa, hay que releer. */
    @Volatile private var cachedAccountKey: String? = null

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
        val cuenta = accountKey(context)
        cachedItems?.let { if (cachedAccountKey == cuenta) return it }
        synchronized(this) {
            cachedItems?.let { if (cachedAccountKey == cuenta) return it }
            migrateLegacyIfNeeded(context, cuenta)
            val items = readFromDisk(context)
            cachedItems = items
            cachedKeys = keysOf(items)
            cachedAccountKey = cuenta
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
        val ahora = System.currentTimeMillis()

        val updated = if (yaEstaba) {
            // Se registra CUÁNDO se sacó: es lo que permite que este borrado
            // se propague a otros equipos en vez de que el sync lo resucite
            // (ver mergeFromRemote). Sin este registro, "combinar sin pisar
            // nada" significa literalmente que un favorito sacado acá volvía
            // a aparecer en cuanto se sincronizaba con un equipo que todavía
            // lo tenía.
            marcarBorrado(context, key, ahora)
            current.filterNot { uniqueKey(it) == key }
        } else {
            quitarMarcaBorrado(context, key)
            // progress es un dato transitorio de "Continuar viendo" (ver
            // ContentItem.progress); si no se despoja acá, la barrita de
            // avance queda pegada en la grilla de Favoritos con el % de
            // cuando se marcó, aunque después se siga viendo o se reinicie.
            current + item.copy(progress = null, syncUpdatedAt = ahora)
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
            cachedAccountKey = accountKey(context)
        }
        prefs(context).edit().putString(KEY, gson.toJson(items)).apply()
        DataSync.scheduleBackup(context)
    }

    /**
     * Obliga a releer del disco en la próxima consulta.
     *
     * Hace falta si algo cambia las preferencias por fuera de esta clase (por
     * ejemplo una restauración de copia de seguridad o un borrado de datos).
     * Cambiar de cuenta NO necesita esto: [load] ya lo detecta solo.
     */
    fun invalidate() {
        synchronized(this) {
            cachedItems = null
            cachedKeys = emptySet()
            cachedAccountKey = null
        }
    }

    // ---------------- Marcas de borrado (para que el sync propague quitar) ----------------

    /**
     * Cuándo se sacó de favoritos por última vez cada "TIPO:ID" que ya no
     * está en la lista actual. Se sube junto con los favoritos (ver
     * DataSync.Payload.favoritosBorrados) para que un equipo que todavía
     * tenga ese ítem sepa que hay que sacarlo, en vez de mantenerlo para
     * siempre por el simple hecho de no haber estado ahí cuando se lo sacó
     * en otro lado.
     */
    fun tombstones(context: Context): Map<String, Long> {
        val json = prefs(context).getString(KEY_TOMBSTONES, null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, Long>>() {}.type
        return runCatching { gson.fromJson<Map<String, Long>>(json, type) }.getOrNull().orEmpty()
    }

    private fun guardarTombstones(context: Context, mapa: Map<String, Long>) {
        val corte = System.currentTimeMillis() - TOMBSTONE_MAX_AGE_MS
        val podado = mapa.filterValues { it >= corte }
        prefs(context).edit().putString(KEY_TOMBSTONES, gson.toJson(podado)).apply()
    }

    private fun marcarBorrado(context: Context, key: String, cuando: Long) {
        guardarTombstones(context, tombstones(context) + (key to cuando))
    }

    private fun quitarMarcaBorrado(context: Context, key: String) {
        val actuales = tombstones(context)
        if (key in actuales) guardarTombstones(context, actuales - key)
    }

    /**
     * Combina lo que bajó de la nube (ver DataSync) con lo que ya hay en este
     * equipo, resolviendo cada "TIPO:ID" por separado: gana el evento más
     * nuevo entre "está marcado como favorito desde [syncUpdatedAt]" (local o
     * remoto) y "se sacó de favoritos en [remoteTombstones]" (local o
     * remoto). Así un favorito sacado en otro equipo se saca acá también (y
     * viceversa), en vez de que "combinar" signifique "nunca borrar".
     */
    fun mergeFromRemote(
        context: Context,
        remoto: List<ContentItem>,
        remoteTombstones: Map<String, Long> = emptyMap()
    ): List<ContentItem> {
        val local = load(context)
        val localTombstones = tombstones(context)

        val claves = HashSet<String>(local.size + remoto.size)
        local.forEach { claves += uniqueKey(it) }
        remoto.forEach { claves += uniqueKey(it) }
        claves += localTombstones.keys
        claves += remoteTombstones.keys

        val localPorClave = local.associateBy { uniqueKey(it) }
        val remotoPorClave = remoto.associateBy { uniqueKey(it) }

        val resultado = mutableListOf<ContentItem>()
        val tombstonesFinal = HashMap<String, Long>()

        for (key in claves) {
            val itemLocal = localPorClave[key]
            val itemRemoto = remotoPorClave[key]
            val borradoLocal = localTombstones[key]
            val borradoRemoto = remoteTombstones[key]

            // Evento más reciente de cada lado: "presente desde X" o "borrado en X".
            val eventoLocal = when {
                itemLocal != null -> itemLocal.syncUpdatedAt to itemLocal
                borradoLocal != null -> borradoLocal to null
                else -> null
            }
            val eventoRemoto = when {
                itemRemoto != null -> itemRemoto.syncUpdatedAt to itemRemoto
                borradoRemoto != null -> borradoRemoto to null
                else -> null
            }

            val ganador = when {
                eventoLocal == null -> eventoRemoto
                eventoRemoto == null -> eventoLocal
                eventoRemoto.first > eventoLocal.first -> eventoRemoto
                else -> eventoLocal
            } ?: continue

            val (cuando, item) = ganador
            if (item != null) {
                resultado += item
            } else {
                tombstonesFinal[key] = cuando
            }
        }

        save(context, resultado)
        guardarTombstones(context, tombstonesFinal)
        return resultado
    }

    /**
     * Identifica la cuenta activa (servidor + usuario). No es información
     * sensible ni se muestra en ningún lado -- es solo la clave que separa un
     * archivo de preferencias del otro.
     */
    private fun accountKey(context: Context): String {
        val server = com.miiptv.app.api.Session.server(context)
        val user = com.miiptv.app.api.Session.username(context)
        return "$server|$user"
            .replace(Regex("[^A-Za-z0-9]"), "_")
            .take(80)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("miiptv_favorites_${accountKey(context)}", Context.MODE_PRIVATE)

    /** Ver la nota "POR QUÉ AHORA HAY UN ARCHIVO POR CUENTA" más arriba. */
    private fun migrateLegacyIfNeeded(context: Context, cuenta: String) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (legacy.getBoolean(LEGACY_MIGRATED_KEY, false)) return
        legacy.edit().putBoolean(LEGACY_MIGRATED_KEY, true).apply()  // una sola vez, pase lo que pase

        val json = legacy.getString(KEY, null) ?: return
        val destino = prefs(context)
        if (destino.contains(KEY)) return   // no pisar favoritos que ya se hayan guardado ahí
        destino.edit().putString(KEY, json).apply()
    }
}
