package com.miiptv.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La intro va sincronizada con el sonido: el golpe grave cae en [IntroTimeline.HIT].
 * Estos tests cuidan que el logo no aparezca antes del golpe, que se vea asentado en
 * el medio, que desaparezca al final y que ningún valor se salga de rango.
 */
class IntroTimelineTest {

    private val pasos = (0..360).map { it / 100f }

    @Test
    fun `el logo no se ve antes del golpe y se ve asentado despues`() {
        assertEquals(0f, IntroTimeline.logoAlpha(0f), 0f)
        assertEquals(0f, IntroTimeline.logoAlpha(IntroTimeline.HIT - 0.01f), 0f)
        assertEquals(1f, IntroTimeline.logoAlpha(1.5f), 0.001f)
        assertEquals(1f, IntroTimeline.logoScale(1.08f), 0.001f)
    }

    @Test
    fun `al final el logo desaparece`() {
        assertEquals(0f, IntroTimeline.logoAlpha(IntroTimeline.DURATION), 0.001f)
        assertEquals(0f, IntroTimeline.glowAlpha(IntroTimeline.DURATION), 0.001f)
    }

    @Test
    fun `el destello llega a su maximo justo despues del golpe`() {
        assertEquals(0f, IntroTimeline.flashAlpha(IntroTimeline.HIT), 0.001f)
        assertEquals(0.85f, IntroTimeline.flashAlpha(0.648f), 0.001f)
        assertEquals(0f, IntroTimeline.flashAlpha(1.5f), 0f)
    }

    @Test
    fun `la linea de luz termina antes del golpe`() {
        assertTrue(IntroTimeline.lineAlpha(0.3f) > 0.5f)
        assertEquals(0f, IntroTimeline.lineAlpha(0.7f), 0f)
    }

    @Test
    fun `la onda de choque nace con el golpe y se apaga sola`() {
        assertEquals(0f, IntroTimeline.ringAlpha(0.3f), 0f)
        assertTrue(IntroTimeline.ringAlpha(0.9f) > 0f)
        assertEquals(0f, IntroTimeline.ringAlpha(2f), 0f)
        assertTrue(IntroTimeline.ringScale(1.5f) > IntroTimeline.ringScale(0.9f))
    }

    @Test
    fun `el reflejo cruza el logo de izquierda a derecha y despues no se ve`() {
        assertEquals(-1f, IntroTimeline.sweep(0.5f), 0f)
        assertEquals(0f, IntroTimeline.sweep(0.72f), 0.001f)
        assertEquals(1f, IntroTimeline.sweep(1.512f), 0.001f)
        assertEquals(-1f, IntroTimeline.sweep(2f), 0f)
        assertTrue(IntroTimeline.sweep(1.0f) < IntroTimeline.sweep(1.3f))
    }

    @Test
    fun `ningun valor se sale de rango en toda la duracion`() {
        for (t in pasos) {
            for (a in listOf(IntroTimeline.glowAlpha(t), IntroTimeline.lineAlpha(t),
                IntroTimeline.flashAlpha(t), IntroTimeline.ringAlpha(t), IntroTimeline.logoAlpha(t))) {
                assertTrue("alpha=$a en t=$t", a in 0f..1f)
            }
            for (s in listOf(IntroTimeline.glowScale(t), IntroTimeline.ringScale(t),
                IntroTimeline.logoScale(t), IntroTimeline.lineScaleX(t))) {
                assertTrue("escala=$s en t=$t", s > 0f && s.isFinite())
            }
        }
    }
}
