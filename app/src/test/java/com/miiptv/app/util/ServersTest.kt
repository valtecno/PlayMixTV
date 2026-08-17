package com.miiptv.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La lista de servidores ahora se escribe a mano en gradle.properties, así que
 * un error de tipeo ahí (una barra de más, un campo que falta) llega hasta la
 * app. Estos tests fijan qué pasa en cada caso: lo válido se lee, lo roto se
 * descarta sin arrastrar al resto.
 */
class ServersTest {

    private val config =
        "l|Sistema L|http://xdplayer.tv:8080|cinema hd hq|sudamericano,sudamericana|vod estrenos,estrenos;" +
            "xl|Sistema XL|http://moontools.site:8080|cinema latino|chile primera|2026"

    @Test
    fun `lee las dos entradas con todos sus campos`() {
        val servers = Servers.parse(config)

        assertEquals(2, servers.size)
        assertEquals("l", servers[0].id)
        assertEquals("Sistema L", servers[0].label)
        assertEquals("http://xdplayer.tv:8080", servers[0].url)
        assertEquals(listOf("cinema hd hq"), servers[0].preferidasCanales)
        assertEquals(listOf("sudamericano", "sudamericana"), servers[0].preferidasPpv)
        assertEquals(listOf("vod estrenos", "estrenos"), servers[0].preferidasPeliculas)
        assertEquals("xl", servers[1].id)
        assertEquals(listOf("2026"), servers[1].preferidasPeliculas)
    }

    @Test
    fun `los espacios alrededor de cada campo no cuentan`() {
        val servers = Servers.parse("  l  |  Sistema L  |  http://x.tv:8080  |  a , b  ")

        assertEquals("l", servers[0].id)
        assertEquals("Sistema L", servers[0].label)
        assertEquals("http://x.tv:8080", servers[0].url)
        assertEquals(listOf("a", "b"), servers[0].preferidasCanales)
    }

    @Test
    fun `la barra final de la URL se saca al leer, no al comparar`() {
        // Importa porque Session guarda la URL normalizada y byUrl compara texto:
        // si una quedara con barra y la otra no, el servidor "desaparecería".
        val servers = Servers.parse("l|Sistema L|http://x.tv:8080/")
        assertEquals("http://x.tv:8080", servers[0].url)
    }

    @Test
    fun `los campos de preferidas son opcionales`() {
        val servers = Servers.parse("l|Sistema L|http://x.tv:8080")

        assertEquals(1, servers.size)
        assertTrue(servers[0].preferidasCanales.isEmpty())
        assertTrue(servers[0].preferidasPpv.isEmpty())
        assertTrue(servers[0].preferidasPeliculas.isEmpty())
    }

    @Test
    fun `una entrada incompleta se descarta sin tumbar a las demas`() {
        // "solo-id" no tiene etiqueta ni URL: es basura, pero la de al lado sirve.
        val servers = Servers.parse("solo-id;xl|Sistema XL|http://ok.tv:8080")

        assertEquals(1, servers.size)
        assertEquals("xl", servers[0].id)
    }

    @Test
    fun `una configuracion vacia da una lista vacia, no una excepcion`() {
        // Este es el caso real de compilar sin -Pplaymix.servers. La app tiene
        // que poder mostrar un mensaje, no cerrarse en el arranque.
        assertTrue(Servers.parse("").isEmpty())
        assertTrue(Servers.parse("   ").isEmpty())
        assertTrue(Servers.parse(";;;").isEmpty())
    }

    @Test
    fun `ids repetidos no generan duplicados`() {
        val servers = Servers.parse(
            "l|Primero|http://uno.tv:8080;l|Segundo|http://dos.tv:8080"
        )

        assertEquals(1, servers.size)
        assertEquals("Primero", servers[0].label)
    }

    @Test
    fun `preferidas devuelve la lista de cada seccion`() {
        val s = Servers.parse(config)[0]

        assertEquals(listOf("cinema hd hq"), s.preferidas(Servers.Preferidas.CANALES))
        assertEquals(listOf("sudamericano", "sudamericana"), s.preferidas(Servers.Preferidas.PPV))
        assertEquals(listOf("vod estrenos", "estrenos"), s.preferidas(Servers.Preferidas.PELICULAS))
    }

    @Test
    fun `una URL desconocida no rompe la busqueda`() {
        val servers = Servers.parse(config)
        assertNull(servers.firstOrNull { it.url == "http://otro.tv:8080" })
    }
}
