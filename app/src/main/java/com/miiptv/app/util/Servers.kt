package com.miiptv.app.util

/**
 * Servidores propios de PlayMix TV. El usuario nunca ve ni escribe la URL:
 * elige "Sistema L" o "Sistema XL" y listo.
 * Para agregar o cambiar servidores, editá solo esta lista.
 */
object Servers {

    data class Server(val label: String, val url: String)

    val all = listOf(
        Server("Sistema L", "http://xdplayer.tv:8080"),
        Server("Sistema XL", "http://moontools.site:8080")
    )

    val default: Server get() = all.first()

    /** Nombre visible del servidor a partir de su URL (para no mostrar la URL nunca). */
    fun labelFor(url: String): String {
        val clean = url.trim().removeSuffix("/")
        return all.firstOrNull { it.url.removeSuffix("/") == clean }?.label ?: "Servidor propio"
    }

    fun byUrl(url: String): Server? {
        val clean = url.trim().removeSuffix("/")
        return all.firstOrNull { it.url.removeSuffix("/") == clean }
    }
}
