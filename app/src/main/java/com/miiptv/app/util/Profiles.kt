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
        /** Nombre del recurso drawable que contiene la imagen del avatar. Null = usar emoji. */
        val drawableRes: String?,
        /** Emoji de fallback (solo para KIDS o si el drawable no carga). */
        val emoji: String,
        /** Color de fondo del círculo (ARGB hex). */
        val color: Int
    ) {
        // 12 avatares con imagen de persona estilizada
        A1  ("a1",  "avatar_p1",  "👤", 0xFF3D5AFE.toInt()),  // chico azul
        A2  ("a2",  "avatar_p2",  "👤", 0xFFE91E63.toInt()),  // chica rosa
        A3  ("a3",  "avatar_p3",  "👤", 0xFF00897B.toInt()),  // chico teal barba
        A4  ("a4",  "avatar_p4",  "👤", 0xFF7B1FA2.toInt()),  // chica morado
        A5  ("a5",  "avatar_p5",  "👤", 0xFFF57C00.toInt()),  // chico gorra
        A6  ("a6",  "avatar_p6",  "👤", 0xFF0097A7.toInt()),  // chica cola
        A7  ("a7",  "avatar_p7",  "👤", 0xFFC62828.toInt()),  // chico cresta
        A8  ("a8",  "avatar_p8",  "👤", 0xFF558B2F.toInt()),  // chica moño
        A9  ("a9",  "avatar_p9",  "👤", 0xFF4527A0.toInt()),  // chico gafas
        A10 ("a10", "avatar_p10", "👤", 0xFF00695C.toInt()),  // chica afro
        A11 ("a11", "avatar_p11", "👤", 0xFFE65100.toInt()),  // chico capucha
        A12 ("a12", "avatar_p12", "👤", 0xFF6A1B9A.toInt()),  // astronauta
        KIDS("kids", null,        "🧒", 0xFF4DB6AC.toInt()); // turquesa (Niños)

        companion object {
            fun fromId(id: String): Avatar = entries.firstOrNull { it.id == id } ?: A1
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
    private const val KEY_HIDDEN     = "hidden_profile_ids"

    private val gson = Gson()

    // -------------------------------------------------------------------------
    // Perfiles por defecto
    // -------------------------------------------------------------------------

    private fun defaultProfiles(): List<Profile> = listOf(
        Profile("p1",    "Perfil 1", Avatar.A1.id),
        Profile("p2",    "Perfil 2", Avatar.A3.id),
        Profile("p3",    "Perfil 3", Avatar.A4.id),
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
    // Ocultar perfiles (solo móvil): no borra nada, solo deja de mostrarlo en este dispositivo
    // -------------------------------------------------------------------------

    fun hiddenIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()

    /** Perfiles no ocultos (los que muestra el selector en móvil). */
    fun getVisible(context: Context): List<Profile> {
        val hidden = hiddenIds(context)
        return getAll(context).filter { it.profileId !in hidden }
    }

    /** Oculta un perfil. No oculta Niños ni el último perfil visible que no sea de niños. */
    fun hide(context: Context, profile: Profile): Boolean {
        if (profile.isKids) return false
        if (getVisible(context).count { !it.isKids } <= 1) return false
        prefs(context).edit()
            .putStringSet(KEY_HIDDEN, hiddenIds(context) + profile.profileId).apply()
        return true
    }

    fun unhideAll(context: Context) {
        prefs(context).edit().remove(KEY_HIDDEN).apply()
    }

    /**
     * Agrega un nuevo perfil. Genera un profileId único basado en timestamp.
     * No se puede agregar si ya hay 6 perfiles (Kids incluido).
     */
    fun add(context: Context, name: String, avatarId: String): Profile? {
        val current = getAll(context)
        if (current.size >= 6) return null
        val newId = "p_${System.currentTimeMillis()}"
        val profile = Profile(newId, name, avatarId)
        save(context, current + profile)
        return profile
    }

    /**
     * Elimina un perfil. No permite eliminar el perfil de Niños ni el perfil activo
     * si es el único perfil no-Kids. Devuelve true si se eliminó.
     */
    fun delete(context: Context, profile: Profile): Boolean {
        if (profile.isKids) return false
        val current = getAll(context)
        val nonKids = current.filter { !it.isKids }
        if (nonKids.size <= 1) return false   // debe quedar al menos 1 perfil no-Kids
        val updated = current.filter { it.profileId != profile.profileId }
        save(context, updated)
        // Si era el perfil activo, limpiar la sesión activa
        if (active(context)?.profileId == profile.profileId) {
            clearActive(context)
        }
        return true
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
