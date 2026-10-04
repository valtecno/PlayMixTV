package com.miiptv.app.util

import com.miiptv.app.util.Profiles.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reglas del selector de perfiles que no dependen de Android:
 * qué perfiles se pueden ocultar o eliminar y cuántos se pueden crear.
 *
 * La regla que más importa: el perfil de Niños nunca se toca y siempre debe
 * quedar al menos un perfil "normal" a la vista, para no dejar la app sin
 * un perfil con el que entrar.
 */
class ProfilesRulesTest {

    private val principal = Profile("p1", "Principal", "A1")
    private val otro      = Profile("p_2", "Otro", "A2")
    private val tercero   = Profile("p_3", "Tercero", "A3")
    private val ninos     = Profile("kids", "Niños", "KIDS", isKids = true)

    @Test
    fun `el perfil de ninos nunca se puede ocultar ni eliminar`() {
        assertFalse(Profiles.canRemove(ninos, listOf(principal, otro, ninos)))
    }

    @Test
    fun `no se puede quitar el ultimo perfil normal`() {
        assertFalse(Profiles.canRemove(principal, listOf(principal, ninos)))
    }

    @Test
    fun `un perfil normal se puede quitar si queda otro normal`() {
        assertTrue(Profiles.canRemove(otro, listOf(principal, otro, ninos)))
        assertTrue(Profiles.canRemove(principal, listOf(principal, otro, ninos)))
    }

    @Test
    fun `quitar de a uno nunca deja la app sin perfil normal`() {
        var visibles = listOf(principal, otro, tercero, ninos)
        // Se oculta todo lo que la regla permita, en orden.
        for (p in listOf(principal, otro, tercero)) {
            if (Profiles.canRemove(p, visibles)) visibles = visibles - p
        }
        assertEquals(1, visibles.count { !it.isKids })
        assertTrue(visibles.contains(ninos))
    }

    @Test
    fun `se puede agregar hasta el maximo de perfiles visibles`() {
        assertTrue(Profiles.canAdd(0))
        assertTrue(Profiles.canAdd(Profiles.MAX_PROFILES - 1))
        assertFalse(Profiles.canAdd(Profiles.MAX_PROFILES))
        assertFalse(Profiles.canAdd(Profiles.MAX_PROFILES + 1))
    }

    @Test
    fun `el maximo de perfiles sigue siendo 6`() {
        assertEquals(6, Profiles.MAX_PROFILES)
    }

    @Test
    fun `ocultar un perfil libera un lugar para agregar otro`() {
        val todos = listOf(principal, otro, tercero, ninos,
            Profile("p_5", "Cinco", "A4"), Profile("p_6", "Seis", "A5"))
        assertFalse(Profiles.canAdd(todos.size))
        val visibles = todos - tercero      // tercero queda oculto
        assertTrue(Profiles.canAdd(visibles.size))
    }
}
