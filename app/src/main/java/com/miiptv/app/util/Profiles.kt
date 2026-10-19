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
        // 24 avatares de personas en estilo caricatura (el último es un perrito)
        A1 ("a1", "avatar_p1", "👤", 0xFFB9E4A6.toInt()),
        A2 ("a2", "avatar_p2", "👤", 0xFFC9A6D8.toInt()),
        A3 ("a3", "avatar_p3", "👤", 0xFFCDEBF0.toInt()),
        A4 ("a4", "avatar_p4", "👤", 0xFFFFE3B0.toInt()),
        A5 ("a5", "avatar_p5", "👤", 0xFF6FCFC3.toInt()),
        A6 ("a6", "avatar_p6", "👤", 0xFFE58FB0.toInt()),
        A7 ("a7", "avatar_p7", "👤", 0xFFD8EEF5.toInt()),
        A8 ("a8", "avatar_p8", "👤", 0xFFFFE8B8.toInt()),
        A9 ("a9", "avatar_p9", "👤", 0xFFBCD8F0.toInt()),
        A10("a10", "avatar_p10", "👤", 0xFFF4A6C8.toInt()),
        A11("a11", "avatar_p11", "👤", 0xFFCFEFF6.toInt()),
        A12("a12", "avatar_p12", "👤", 0xFFF08A00.toInt()),
        A13("a13", "avatar_p13", "👤", 0xFF9EB8D0.toInt()),
        A14("a14", "avatar_p14", "👤", 0xFFC9A0DC.toInt()),
        A15("a15", "avatar_p15", "👤", 0xFFC8E6C9.toInt()),
        A16("a16", "avatar_p16", "👤", 0xFFFFCCBC.toInt()),
        A17("a17", "avatar_p17", "👤", 0xFFB3E5FC.toInt()),
        A18("a18", "avatar_p18", "👤", 0xFFFFF59D.toInt()),
        A19("a19", "avatar_p19", "👤", 0xFFD1C4E9.toInt()),
        A20("a20", "avatar_p20", "👤", 0xFF80CBC4.toInt()),
        A21("a21", "avatar_p21", "👤", 0xFFFFAB91.toInt()),
        A22("a22", "avatar_p22", "👤", 0xFFE1BEE7.toInt()),
        A23("a23", "avatar_p23", "👤", 0xFFB0BEC5.toInt()),
        A24("a24", "avatar_p24", "👤", 0xFFC5CAE9.toInt()),
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

    /** Máximo de perfiles visibles a la vez (Niños incluido). Los ocultos no cuentan. */
    const val MAX_PROFILES = 6

    // -------------------------------------------------------------------------
    // Reglas puras (sin Android): se usan arriba y se prueban en los tests
    // -------------------------------------------------------------------------

    /**
     * Un perfil se puede ocultar si no es el de Niños y si después
     * queda al menos otro perfil que no sea de niños entre los [mostrados].
     */
    fun canRemove(profile: Profile, shown: List<Profile>): Boolean =
        !profile.isKids && shown.count { !it.isKids } > 1

    /** Se puede agregar otro perfil mientras los visibles no lleguen al máximo. */
    fun canAdd(visibleCount: Int): Boolean = visibleCount < MAX_PROFILES

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

    // Una sola vez: compilar el Regex en cada consulta pesaba en cada fila de la lista.
    private val NO_ALFANUMERICO = Regex("[^A-Za-z0-9]")

    private fun accountKey(context: Context): String {
        val server = Session.server(context)
        val user   = Session.username(context)
        return "$server|$user"
            .replace(NO_ALFANUMERICO, "_")
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
    // Ocultar perfiles: no borra nada, solo deja de mostrarlo en este dispositivo
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
        if (!canRemove(profile, getVisible(context))) return false
        prefs(context).edit()
            .putStringSet(KEY_HIDDEN, hiddenIds(context) + profile.profileId).apply()
        return true
    }

    fun unhideAll(context: Context) {
        prefs(context).edit().remove(KEY_HIDDEN).apply()
    }

    /**
     * Agrega un nuevo perfil. Genera un profileId único basado en timestamp.
     * No se puede agregar si ya hay [MAX_PROFILES] perfiles visibles (Kids incluido);
     * los ocultos en este dispositivo no cuentan.
     */
    fun add(context: Context, name: String, avatarId: String): Profile? {
        val current = getAll(context)
        if (!canAdd(getVisible(context).size)) return null
        val newId = "p_${System.currentTimeMillis()}"
        val profile = Profile(newId, name, avatarId)
        save(context, current + profile)
        return profile
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
