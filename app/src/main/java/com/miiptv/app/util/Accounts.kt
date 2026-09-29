package com.miiptv.app.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Cuentas guardadas, una por servidor. Permite tener una cuenta en Sistema L y
 * otra en Sistema XL, y saltar de una a otra sin volver a escribir la clave.
 *
 * Vive en su propio archivo de preferencias para que "Cerrar sesión" no las borre.
 */
object Accounts {

    private const val PREFS = "miiptv_accounts"
    private const val KEY = "saved"
    private val gson = Gson()

    data class Account(val serverUrl: String, val username: String, val password: String) {
        val serverLabel: String get() = Servers.labelFor(serverUrl)
    }

    fun getAll(context: Context): List<Account> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        val type = object : TypeToken<List<Account>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Guarda (o actualiza) la cuenta de ese servidor. Solo una cuenta por servidor. */
    fun save(context: Context, serverUrl: String, username: String, password: String) {
        val clean = serverUrl.trim().removeSuffix("/")
        val updated = getAll(context)
            .filter { it.serverUrl.removeSuffix("/") != clean }
            .toMutableList()
        updated.add(Account(clean, username.trim(), password.trim()))
        persist(context, updated)
    }

    fun remove(context: Context, account: Account) {
        persist(context, getAll(context).filter {
            it.serverUrl != account.serverUrl || it.username != account.username
        })
    }

    /** Cuentas distintas a la que está activa ahora mismo. */
    fun others(context: Context): List<Account> {
        val server = com.miiptv.app.api.Session.server(context).removeSuffix("/")
        val user = com.miiptv.app.api.Session.username(context)
        return getAll(context).filterNot {
            it.serverUrl.removeSuffix("/") == server && it.username == user
        }
    }

    private fun persist(context: Context, list: List<Account>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, gson.toJson(list))
            .apply()
    }
}
