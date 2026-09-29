package com.miiptv.app.util

import android.content.Context
import com.miiptv.app.BuildConfig
import com.miiptv.app.api.Session

/**
 * Servidores propios de PlayMix TV. El usuario nunca ve ni escribe la URL:
 * elige "Sistema L" o "Sistema XL" y listo.
 *
 * ---------------------------------------------------------------------------
 * DÓNDE SE EDITA LA LISTA
 *
 * En **gradle.properties**, propiedad `playmix.servers`. Acá solo está el
 * código que la interpreta.
 *
 * Antes las direcciones estaban escritas en este archivo y el resto del
 * proyecto las usaba por posición (`Servers.all[0]`, `Servers.all[1]`). Eso
 * traía dos problemas: cambiar de servidor obligaba a tocar código, y agregar
 * un tercero rompía en silencio la lógica que dependía del orden. Ahora cada
 * servidor tiene un [Server.id] estable y todo se resuelve por ese id.
 *
 * Formato de cada entrada (separadas por ";", campos por "|"):
 *
 *     id | etiqueta | url | preferidas_canales | preferidas_ppv | preferidas_pelis
 *
 * Los tres últimos campos son opcionales y admiten varios valores separados
 * por comas.
 * ---------------------------------------------------------------------------
 */
object Servers {

    /**
     * Etiqueta para una cuenta guardada cuyo servidor ya no está en la lista
     * (por ejemplo, se dio de baja un sistema y el usuario todavía tiene la
     * cuenta guardada). Nunca se muestra la URL.
     */
    const val ETIQUETA_DESCONOCIDA = "Servidor propio"

    /** Secciones que pueden tener una carpeta preferida por servidor. */
    enum class Preferidas { CANALES, PPV, PELICULAS }

    data class Server(
        /** Clave interna estable. No cambia aunque cambie la etiqueta o la URL. */
        val id: String,
        /** Lo único que ve el usuario. */
        val label: String,
        val url: String,
        val preferidasCanales: List<String> = emptyList(),
        val preferidasPpv: List<String> = emptyList(),
        val preferidasPeliculas: List<String> = emptyList()
    ) {
        /** Carpetas a poner primero en esa sección, o lista vacía si no hay. */
        fun preferidas(seccion: Preferidas): List<String> = when (seccion) {
            Preferidas.CANALES -> preferidasCanales
            Preferidas.PPV -> preferidasPpv
            Preferidas.PELICULAS -> preferidasPeliculas
        }
    }

    /**
     * Se calcula una sola vez, la primera vez que alguien la pide. Es `by lazy`
     * a propósito: así los tests pueden llamar a [parse] sin que se toque
     * BuildConfig.
     */
    val all: List<Server> by lazy { parse(BuildConfig.SERVERS) }

    /** Servidor preseleccionado en el login. Null solo si la lista está vacía. */
    val default: Server? get() = all.firstOrNull()

    /**
     * Interpreta el texto de configuración. Es tolerante a propósito: una
     * entrada mal escrita se descarta en vez de tirar abajo toda la lista y
     * dejar la app sin servidores.
     */
    fun parse(config: String): List<Server> =
        config.split(';')
            .mapNotNull { entrada ->
                val campos = entrada.split('|').map { it.trim() }
                val id = campos.getOrNull(0).orEmpty()
                val label = campos.getOrNull(1).orEmpty()
                val url = campos.getOrNull(2).orEmpty().trimEnd('/')
                // Sin id, etiqueta y URL no hay servidor utilizable.
                if (id.isEmpty() || label.isEmpty() || url.isEmpty()) return@mapNotNull null
                Server(
                    id = id,
                    label = label,
                    url = url,
                    preferidasCanales = lista(campos.getOrNull(3)),
                    preferidasPpv = lista(campos.getOrNull(4)),
                    preferidasPeliculas = lista(campos.getOrNull(5))
                )
            }
            // Si por error quedan dos entradas con el mismo id, gana la primera.
            .distinctBy { it.id }

    private fun lista(campo: String?): List<String> =
        campo.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Nombre visible del servidor a partir de su URL (para no mostrar la URL nunca). */
    fun labelFor(url: String): String = byUrl(url)?.label ?: ETIQUETA_DESCONOCIDA

    fun byUrl(url: String): Server? {
        val limpia = url.trim().trimEnd('/')
        return all.firstOrNull { it.url == limpia }
    }

    fun byId(id: String): Server? = all.firstOrNull { it.id == id }

    /** Servidor conectado ahora mismo, o null si la cuenta apunta a otro lado. */
    fun current(context: Context): Server? = byUrl(Session.server(context))

    /** Etiqueta del servidor conectado ahora mismo ("Sistema L", "Sistema XL"...). */
    fun currentLabel(context: Context): String? = current(context)?.label
}
