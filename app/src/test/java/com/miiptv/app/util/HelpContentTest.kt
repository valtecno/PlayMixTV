package com.miiptv.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El asistente de ayuda tiene que acertar con las preguntas escritas "como
 * habla la gente": con o sin tildes, con palabras de relleno, en singular o
 * plural. Cada caso fija cuál debe ser la PRIMERA respuesta (la que se
 * muestra ya abierta).
 */
class HelpContentTest {

    private fun primera(consulta: String): String =
        HelpContent.buscar(consulta).firstOrNull()?.pregunta.orEmpty()

    @Test
    fun `preguntas comunes llegan a la respuesta correcta`() {
        assertEquals("¿Cómo agrego algo a Favoritos?", primera("cómo agrego favoritos"))
        assertEquals("Se escucha muy bajo o no se escucha", primera("no se escucha"))
        assertEquals("¿Cómo cambio el idioma del audio o pongo subtítulos?", primera("como pongo subtitulos"))
        assertEquals("El video se corta o se queda cargando", primera("se corta el video"))
        assertEquals("Olvidé mi PIN, ¿qué hago?", primera("olvide mi pin"))
        assertEquals("¿Cómo activo el Perfil de niños?", primera("activar niños"))
        assertEquals("¿Cómo salgo del Perfil de niños?", primera("como salgo del perfil de niños"))
        assertEquals("¿Qué hay en Deportes - PPV?", primera("ver partido de futbol"))
        assertEquals("La imagen se ve cortada o estirada", primera("la imagen se ve estirada"))
        assertEquals("¿Cómo veo varios canales a la vez?", primera("ver 4 canales"))
        assertEquals("¿Cómo lo veo en el televisor con Chromecast?", primera("chromecast"))
    }

    @Test
    fun `distingue actualizar el contenido de actualizar la app`() {
        assertEquals("¿Cómo actualizo el contenido?", primera("actualizar"))
        assertEquals("¿Cómo actualizo la aplicación?", primera("actualizar app"))
    }

    @Test
    fun `distingue renovar el acceso de cambiar de cuenta`() {
        assertEquals("¿Cómo renuevo mi acceso o contacto a soporte?", primera("renovar mi cuenta"))
        assertEquals("¿Cómo cambio de cuenta o agrego otra?", primera("cambiar de cuenta"))
    }

    @Test
    fun `problemas de carga`() {
        assertEquals("La app no carga el contenido", primera("no carga nada"))
        assertEquals("Dice que el panel rechazó la conexión", primera("rechazo la conexion"))
    }

    @Test
    fun `tildes y mayusculas no cambian el resultado`() {
        assertEquals(primera("subtitulos"), primera("SUBTÍTULOS"))
        assertEquals(primera("niños"), primera("ninos"))
    }

    @Test
    fun `sin palabras utiles devuelve todas`() {
        assertEquals(HelpContent.preguntas.size, HelpContent.buscar("").size)
        assertEquals(HelpContent.preguntas.size, HelpContent.buscar("como que el").size)
    }

    @Test
    fun `algo que no esta devuelve lista vacia`() {
        assertTrue(HelpContent.buscar("xyzabc").isEmpty())
    }

    @Test
    fun `el tema filtra la lista`() {
        val soloNinos = HelpContent.buscar("", HelpContent.Tema.NINOS)
        assertTrue(soloNinos.isNotEmpty())
        assertTrue(soloNinos.all { it.tema == HelpContent.Tema.NINOS })
    }

    @Test
    fun `cada tema tiene al menos una pregunta`() {
        HelpContent.Tema.values().forEach { tema ->
            assertTrue(tema.etiqueta, HelpContent.preguntas.any { it.tema == tema })
        }
    }
}
