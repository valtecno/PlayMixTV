package com.miiptv.app.util

import android.content.Context
import java.util.Calendar
import java.util.TimeZone

/**
 * Decide cuándo toca refrescar el catálogo entero: **una vez al día, a las 3 de
 * la mañana hora de Chile**.
 *
 * ---------------------------------------------------------------------------
 * LA IDEA: "DÍA LÓGICO", NO "CADA 24 HORAS"
 *
 * Guardar "la última vez que refresqué" y comparar contra 24 horas no sirve:
 * el momento del refresco se iría corriendo solo. Si un día abro a las 21:00,
 * el siguiente no tocaría hasta las 21:00, y a la semana el refresco estaría en
 * cualquier hora menos a las 3.
 *
 * En vez de eso se guarda **qué día lógico** se refrescó por última vez, donde
 * el día lógico cambia a las 3 AM en lugar de a medianoche:
 *
 *     lunes 02:59  → día lógico DOMINGO
 *     lunes 03:00  → día lógico LUNES
 *     lunes 23:00  → día lógico LUNES
 *
 * Toca refrescar cuando el día lógico guardado no es el de ahora. Con eso los
 * dos casos que se pidieron salen del mismo cálculo, sin código aparte:
 *
 *  - La app está abierta y cruza las 3 AM → cambia el día lógico → toca.
 *  - La app estaba cerrada y se abre a las 9 AM → el día guardado es el de ayer
 *    → toca en la primera apertura, y no vuelve a tocar hasta las 3 AM
 *    siguientes por más veces que se abra y cierre.
 *
 * ---------------------------------------------------------------------------
 * POR QUÉ LA ZONA VA FIJA A CHILE
 *
 * Se usa `America/Santiago` explícitamente en vez de la zona del aparato. Los
 * decodificadores baratos llegan de fábrica en UTC o en una zona de Asia y
 * mucha gente nunca la corrige; si se usara la hora local del aparato, el
 * refresco caería a cualquier hora. La hora del panel es la de Chile, así que
 * el corte se ata a Chile y da igual cómo esté configurado el aparato o si el
 * usuario está de viaje.
 *
 * `America/Santiago` ya contempla el horario de verano, así que las 3 AM son
 * las 3 AM del reloj todo el año. Un aparato con la base de zonas horarias
 * vieja puede desviarse una hora en las semanas del cambio, y para un refresco
 * de madrugada eso da igual: se hace a las 2 o a las 4 y nadie lo nota.
 * (Nota: es la hora de Chile continental. Magallanes y Pascua tienen la suya,
 * pero el catálogo no depende de eso.)
 *
 * ---------------------------------------------------------------------------
 * POR QUÉ `Calendar` Y NO `java.time`
 *
 * `LocalDate` y compañía necesitan API 26, o desugaring de la biblioteca base.
 * El proyecto está en minSdk 21 y no tiene desugaring activado, así que
 * `java.time` ni siquiera compilaría. `Calendar` es más incómodo pero funciona
 * desde API 1.
 * ---------------------------------------------------------------------------
 */
object DailyRefresh {

    /** Hora del corte diario, en hora de Chile. */
    const val HORA_CORTE = 3

    private const val ZONA_CHILE = "America/Santiago"

    /**
     * Preferencias propias, aparte de las de la sesión.
     *
     * No van en `miiptv_prefs` a propósito: ese archivo está excluido de la
     * copia de seguridad por guardar la contraseña, y se borra entero al cerrar
     * sesión. La marca del refresco no tiene nada que ver con la cuenta.
     */
    private const val PREFS = "miiptv_refresh"
    private const val KEY_ULTIMO_DIA = "ultimo_dia_refrescado"

    private fun zona(): TimeZone = TimeZone.getTimeZone(ZONA_CHILE)

    /**
     * Día lógico de un instante, como número `aaaammdd` (por ejemplo 20260818).
     *
     * Se usa un entero y no una fecha para poder guardarlo y compararlo sin
     * depender de ningún formato ni de la configuración regional del aparato.
     */
    fun diaLogico(ahora: Long = System.currentTimeMillis()): Int {
        val c = Calendar.getInstance(zona())
        c.timeInMillis = ahora
        // Antes del corte todavía se cuenta como el día anterior.
        if (c.get(Calendar.HOUR_OF_DAY) < HORA_CORTE) c.add(Calendar.DAY_OF_YEAR, -1)
        return c.get(Calendar.YEAR) * 10000 +
            (c.get(Calendar.MONTH) + 1) * 100 +
            c.get(Calendar.DAY_OF_MONTH)
    }

    /**
     * Milisegundos que faltan para el próximo corte de las 3 AM de Chile.
     *
     * Sirve para programar el aviso mientras la app está abierta. Si ahora ya
     * pasaron las 3, apunta a las 3 de mañana.
     */
    fun msHastaProximoCorte(ahora: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance(zona())
        c.timeInMillis = ahora
        c.set(Calendar.HOUR_OF_DAY, HORA_CORTE)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= ahora) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis - ahora
    }

    /**
     * ¿Toca el refresco del día?
     *
     * La primera vez de todas devuelve true (no hay nada guardado), así que una
     * instalación nueva arranca con catálogo recién traído del panel.
     */
    fun toca(context: Context, ahora: Long = System.currentTimeMillis()): Boolean =
        prefs(context).getInt(KEY_ULTIMO_DIA, 0) != diaLogico(ahora)

    /**
     * Anota que el día de hoy ya se refrescó.
     *
     * Se llama al LANZAR la descarga, no al terminarla. Si se marcara al
     * terminar, un panel caído a las 3 AM dejaría el día sin marcar y cada
     * apertura posterior volvería a forzar la descarga completa: justo el
     * castigo que no hay que darle a alguien con mala conexión. Si esta vez
     * falla, se reintenta al día siguiente, y mientras tanto sigue en pie el
     * refresco normal cada 30 minutos y el botón de actualizar a mano.
     */
    fun marcarHecho(context: Context, ahora: Long = System.currentTimeMillis()) {
        prefs(context).edit().putInt(KEY_ULTIMO_DIA, diaLogico(ahora)).apply()
    }

    /** Olvida la marca: el próximo arranque volverá a traer el catálogo entero. */
    fun reset(context: Context) {
        prefs(context).edit().remove(KEY_ULTIMO_DIA).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
