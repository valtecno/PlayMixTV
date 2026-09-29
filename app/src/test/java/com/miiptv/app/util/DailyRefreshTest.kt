package com.miiptv.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * El refresco diario es de los que no se pueden probar a mano: para ver si el
 * corte de las 3 AM funciona habría que quedarse despierto, y para ver si el
 * cambio de horario de verano lo rompe habría que esperar a septiembre. Por eso
 * la lógica de fechas está aislada en funciones puras que reciben el instante:
 * acá se le pasan las fechas difíciles y se comprueba el resultado al momento.
 */
class DailyRefreshTest {

    private val chile = TimeZone.getTimeZone("America/Santiago")

    /** Instante correspondiente a una hora del reloj chileno. */
    private fun enChile(año: Int, mes: Int, dia: Int, hora: Int, minuto: Int = 0): Long {
        val c = Calendar.getInstance(chile)
        c.clear()
        c.set(año, mes - 1, dia, hora, minuto, 0)
        return c.timeInMillis
    }

    // ---------------- El corte cae a las 3, no a medianoche ----------------

    @Test
    fun `justo antes de las 3 todavia cuenta como el dia anterior`() {
        assertEquals(20260817, DailyRefresh.diaLogico(enChile(2026, 8, 18, 2, 59)))
    }

    @Test
    fun `a las 3 en punto empieza el dia nuevo`() {
        assertEquals(20260818, DailyRefresh.diaLogico(enChile(2026, 8, 18, 3, 0)))
    }

    @Test
    fun `la medianoche NO cambia el dia logico`() {
        // Es la diferencia con un "una vez por día natural": entre las 23:00 y
        // las 00:30 no debe tocar refresco, aunque cambie la fecha del reloj.
        val antesDeMedianoche = DailyRefresh.diaLogico(enChile(2026, 8, 17, 23, 0))
        val despuesDeMedianoche = DailyRefresh.diaLogico(enChile(2026, 8, 18, 0, 30))
        assertEquals(antesDeMedianoche, despuesDeMedianoche)
    }

    @Test
    fun `todo el dia entre corte y corte es el mismo dia logico`() {
        val alCorte = DailyRefresh.diaLogico(enChile(2026, 8, 18, 3, 0))
        assertEquals(alCorte, DailyRefresh.diaLogico(enChile(2026, 8, 18, 12, 0)))
        assertEquals(alCorte, DailyRefresh.diaLogico(enChile(2026, 8, 18, 23, 59)))
        assertEquals(alCorte, DailyRefresh.diaLogico(enChile(2026, 8, 19, 2, 59)))
        // …y al llegar el corte siguiente, cambia
        assertNotEquals(alCorte, DailyRefresh.diaLogico(enChile(2026, 8, 19, 3, 0)))
    }

    // ---------------- Bordes de calendario ----------------

    @Test
    fun `el cruce de mes retrocede al ultimo dia del mes anterior`() {
        assertEquals(20260831, DailyRefresh.diaLogico(enChile(2026, 9, 1, 1, 0)))
    }

    @Test
    fun `el cruce de año retrocede a fin de diciembre`() {
        assertEquals(20261231, DailyRefresh.diaLogico(enChile(2027, 1, 1, 2, 0)))
    }

    @Test
    fun `el 1 de marzo de un año bisiesto retrocede al 29 de febrero`() {
        assertEquals(20280229, DailyRefresh.diaLogico(enChile(2028, 3, 1, 1, 0)))
    }

    // ---------------- La zona va fija a Chile ----------------

    @Test
    fun `el dia logico no depende de la zona horaria del aparato`() {
        // Un deco recién sacado de la caja suele venir en UTC o en una zona de
        // Asia. El corte tiene que caer igual, porque la hora que importa es la
        // del panel, no la del aparato.
        val instante = enChile(2026, 8, 18, 2, 59)
        val original = TimeZone.getDefault()
        try {
            val enCadaZona = listOf("UTC", "Asia/Shanghai", "America/New_York", "Pacific/Auckland")
                .map {
                    TimeZone.setDefault(TimeZone.getTimeZone(it))
                    DailyRefresh.diaLogico(instante)
                }
            assertEquals(1, enCadaZona.toSet().size)
            assertEquals(20260817, enCadaZona.first())
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // ---------------- Cuenta atrás hasta el próximo corte ----------------

    @Test
    fun `a las 2 de la mañana falta una hora`() {
        val falta = DailyRefresh.msHastaProximoCorte(enChile(2026, 8, 18, 2, 0))
        assertEquals(60 * 60 * 1000L, falta)
    }

    @Test
    fun `pasado el corte apunta al de mañana`() {
        // A las 4 AM faltan 23 horas para las 3 AM del día siguiente.
        val falta = DailyRefresh.msHastaProximoCorte(enChile(2026, 8, 18, 4, 0))
        assertEquals(23 * 60 * 60 * 1000L, falta)
    }

    @Test
    fun `la espera nunca es negativa ni mayor a un dia y medio`() {
        // Recorre un año entero hora a hora, incluidos los dos cambios de horario
        // de verano de Chile. Una espera negativa dispararía el refresco en
        // bucle; una demasiado larga se saltaría un día.
        //
        // El máximo real medido en 2026 son 25 h, el sábado del cambio de abril
        // (Chile atrasa el reloj, así que ese día tiene 25 horas). El tope de 36
        // deja margen para aparatos con la base de zonas horarias desactualizada.
        var t = enChile(2026, 1, 1, 0, 0)
        val fin = enChile(2027, 1, 1, 0, 0)
        val unDiaYMedio = 36 * 60 * 60 * 1000L
        while (t < fin) {
            val falta = DailyRefresh.msHastaProximoCorte(t)
            assertTrue("espera no positiva en $t: $falta", falta > 0)
            assertTrue("espera excesiva en $t: $falta", falta <= unDiaYMedio)
            t += 60 * 60 * 1000L
        }
    }

    @Test
    fun `el dia logico avanza de a uno durante un año entero`() {
        // Comprueba que ningún día se repite ni se saltea, incluidos los dos
        // domingos en que Chile mueve el reloj.
        var t = enChile(2026, 1, 1, 3, 0)
        val fin = enChile(2027, 1, 1, 3, 0)
        val vistos = mutableListOf<Int>()
        while (t < fin) {
            val dia = DailyRefresh.diaLogico(t)
            if (vistos.lastOrNull() != dia) vistos.add(dia)
            t += 60 * 60 * 1000L
        }
        assertEquals("días distintos en 2026", 365, vistos.size)
        assertEquals("ninguno repetido", vistos.size, vistos.toSet().size)
    }
}
