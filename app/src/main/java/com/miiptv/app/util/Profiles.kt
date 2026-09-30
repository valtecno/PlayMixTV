package com.miiptv.app.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.miiptv.app.api.Session

/**
 * Sistema de perfiles de usuario dentro de una cuenta Xtream.
 *
 * Permite que varias personas compartan los mismos datos de acceso (servidor +
 * usuario) pero tengan favoritos, historial y "Continuar viendo" completamente
 * separados entre sí. Cada perfil es dueño de un [profileId] inmutable que
 * actúa como sufijo de la accountKey en Favorites/History/ContinueWatching,
 * de la misma forma en que la cuenta separa datos entre servidores distintos.
 *
 * Los perfiles tienen nombre editable y un avatar elegido de una galería
 * predefinida ([Avatar]). El perfil "Niños" es especial: activa KidsMode
 * automáticamente al seleccionarse.
 *
 * Estructura de almacenamiento:
 *   - Lista de perfiles: SharedPreferences "miiptv_profiles_<accountKey>"
 *   - Perfil activo:     SharedPreferences "miiptv_prefs" clave "active_profile_id"
 *
 * La primera vez que se abre la app con esta versión, si no hay perfiles
 * guardados, se crean los cuatro por defecto. Los favoritos/historial/
 * continuar viendo que ya existían quedan en la accountKey vieja (sin sufijo
 * de perfil): se asignan al primer perfil para no perderlos.
 */
object Profiles {

    // -------------------------------------------------------------------------
    // Avatares predefinidos — índice que se guarda en el perfil
    // -------------------------------------------------------------------------

    enum class Avatar(
        val id: String,
        /** Emoji que se usa como fallback y en el selector pequeño. */
        val emoji: String,
        /** Color de fondo del círculo (ARGB hex). */
        val color: Int
    ) {
        STAR    ("star",    "⭐", 0xFF6C3FD4.toInt()),  // morado
        FLAME   ("flame",   "🔥", 0xFFD4533F.toInt()),  // rojo
        WAVE    ("wave",    "🌊", 0xFF3F8FD4.toInt()),  // azul
        LEAF    ("leaf",    "🌿", 0xFF3FAD5C.toInt()),  // verde
        MOON    ("moon",    "🌙", 0xFF2C2C54.toInt()),  // índigo oscuro
        SUN     ("sun",     "☀️", 0xFFD4A93F.toInt()),  // naranja
        BOLT    ("bolt",    "⚡", 0xFFD4D43F.toInt()),  // amarillo
        HEART   ("heart",   "❤️", 0xFFD43F6C.toInt()),  // rosa
        ROCKET  ("rocket",  "🚀", 0xFF3F5FD4.toInt()),  // azul marino
        PLANET  ("planet",  "🪐", 0xFF8F3FD4.toInt()),  // violeta
        KIDS    ("kids",    "🧒", 0xFF3FD4C4.toInt());  // turquesa (reservado para Niños)

        companion object {
            fun fromId(id: String): Avatar = entries.firstOrNull { it.id == id } ?: STAR
        }
    }

    // -------------------------------------------------------------------------
    // Modelo de datos
    // -------------------------------------------------------------------------

    data class Profile(
        /** Clave inmutable. Se genera una vez y nunca cambia. */
        val profileId: String,
        val name: String,
        val avatarId: String,
        /** true = activa KidsMode automáticamente al seleccionarse. */
        val isKids: Boolean = false
    ) {
        val avatar: Avatar get() = Avatar.fromId(avatarId)
    }

    // -------------------------------------------------------------------------
    // Constantes
    // -------------------------------------------------------------------------

    private const val PREFS_SESSION  = "miiptv_prefs"
    private const val KEY_ACTIVE_ID  = "active_profile_id"
    private const val KEY_PROFILES   = "profiles"

    private val gson = Gson()

    // -------------------------------------------------------------------------
    // Perfiles por defecto
    // -------------------------------------------------------------------------

    private fun defaultProfiles(): List<Profile> = listOf(
        Profile("p1",    "Perfil 1", Avatar.STAR.id),
        Profile("p2",    "Perfil 2", Avatar.FLAME.id),
        Profile("p3",    "Perfil 3", Avatar.WAVE.id),
        Profile("kids",  "Niños",    Avatar.KIDS.id, isKids = true)
    )

    // -------------------------------------------------------------------------
    // Clave de la cuenta activa (mismo patrón que Favorites/History/etc.)
    // -------------------------------------------------------------------------

    private fun accountKey(context: Context): String {
        val server = Session.server(context)
        val user   = Session.username(context)
        return "$server|$user"
            .replace(Regex("[^A-Za-z0-9]"), "_")
            .take(80)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(
            "miiptv_profiles_${accountKey(context)}",
            Context.MODE_PRIVATE
        )

    // -------------------------------------------------------------------------
    // Lectura
    // -------------------------------------------------------------------------

    /** Lista de perfiles de la cuenta activa. Los crea por defecto si no existen. */
    fun getAll(context: Context): List<Profile> {
        val json = prefs(context).getString(KEY_PROFILES, null) ?: return initDefaults(context)
        val type = object : TypeToken<List<Profile>>() {}.type
        val list: List<Profile>? = runCatching { gson.fromJson<List<Profile>>(json, type) }.getOrNull()
        return if (list.isNullOrEmpty()) initDefaults(context) else list
    }

    /** Perfil activo o el primero de la lista si nada está guardado. */
    fun active(context: Context): Profile? {
        val id = context.getSharedPreferences(PREFS_SESSION, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_ID, null)
        val all = getAll(context)
        return if (id != null) all.firstOrNull { it.profileId == id } else null
    }

    // -------------------------------------------------------------------------
    // Escritura
    // -------------------------------------------------------------------------

    fun setActive(context: Context, profile: Profile) {
        context.getSharedPreferences(PREFS_SESSION, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE_ID, profile.profileId).apply()
        // Los singletons de datos deben olvidar su caché: la próxima lectura
        // usará la nueva accountKey (que ya incluye el profileId).
        Favorites.invalidate()
        History.invalidate()
        ContinueWatching.invalidate()
        DataSync.cancelPending()
    }

    /** Limpia el perfil activo (al cerrar sesión). */
    fun clearActive(context: Context) {
        context.getSharedPreferences(PREFS_SESSION, Context.MODE_PRIVATE)
            .edit().remove(KEY_ACTIVE_ID).apply()
    }

    fun save(context: Context, profiles: List<Profile>) {
        prefs(context).edit().putString(KEY_PROFILES, gson.toJson(profiles)).apply()
    }

    fun update(context: Context, updated: Profile) {
        val list = getAll(context).map { if (it.profileId == updated.profileId) updated else it }
        save(context, list)
    }

    // -------------------------------------------------------------------------
    // Inicialización con perfiles por defecto
    // -------------------------------------------------------------------------

    private fun initDefaults(context: Context): List<Profile> {
        val defaults = defaultProfiles()
        save(context, defaults)
        return defaults
    }

    // -------------------------------------------------------------------------
    // Clave que identifica un perfil dentro de una cuenta
    // (la usan Favorites / History / ContinueWatching como sufijo)
    // -------------------------------------------------------------------------

    /**
     * Devuelve la clave compuesta cuenta+perfil que aísla los datos de este
     * perfil de los demás. Si no hay perfil activo devuelve solo la clave de
     * cuenta (compatibilidad hacia atrás: el perfil "p1" hereda los datos
     * que ya había antes de esta versión).
     */
    fun activeKey(context: Context): String {
        val base = accountKey(context)
        val pid  = active(context)?.profileId ?: return base
        // "p1" no añade sufijo: es el heredero de los datos pre-perfiles.
        return if (pid == "p1") base else "${base}_$pid"
    }
}
