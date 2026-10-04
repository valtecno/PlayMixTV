package com.miiptv.app.util

/**
 * Línea de tiempo de la intro de PlayMix TV, sin depender de Android para poder
 * probarla. [IntroActivity] solo aplica estos valores a las vistas en cada cuadro.
 *
 * Todo está en segundos y sincronizado con res/raw/intro_sonido.mp3: el golpe
 * grave cae en [HIT] (0,6 s) y el sonido se apaga hacia [DURATION].
 *
 *   0,00  línea de luz que crece desde el centro
 *   0,61  golpe: el logo aparece con zoom, destello y onda de choque
 *   0,72  reflejo de luz que cruza el logo (hasta 1,51)
 *   1,08  logo asentado, el brillo respira
 *   2,88  fundido de salida (hasta 3,6)
 */
object IntroTimeline {

    const val DURATION = 3.6f
    const val HIT = 0.612f

    private const val LINE_END = 0.66f
    private const val LOGO_IN_END = 1.08f
    private const val LOGO_OUT_START = 2.88f
    private const val SWEEP_START = 0.72f
    private const val SWEEP_END = 1.512f
    private const val RING_START = 0.62f
    private const val RING_LEN = 1.2f

    /** Interpolación lineal por tramos entre puntos (tiempo a valor). */
    private fun kf(t: Float, vararg p: Pair<Float, Float>): Float {
        if (t <= p.first().first) return p.first().second
        for (i in 1 until p.size) {
            if (t <= p[i].first) {
                val a = p[i - 1]
                val b = p[i]
                return a.second + (b.second - a.second) * ((t - a.first) / (b.first - a.first))
            }
        }
        return p.last().second
    }

    /** Frena al final (entra rápido y se asienta). */
    private fun decel(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return 1f - (1f - c) * (1f - c) * (1f - c)
    }

    fun glowAlpha(t: Float) = kf(t, 0f to 0f, 0.612f to 0.4f, 0.648f to 1f, 1.44f to 0.6f,
        1.98f to 0.45f, 2.52f to 0.58f, 2.88f to 0.5f, DURATION to 0f)

    fun glowScale(t: Float) = kf(t, 0f to 0.6f, 0.612f to 0.9f, 0.648f to 1.2f, 1.44f to 1f, DURATION to 1.1f)

    fun lineAlpha(t: Float) = if (t >= LINE_END) 0f else kf(t, 0f to 0f, 0.2f to 0.9f, 0.58f to 1f, LINE_END to 0f)

    fun lineScaleX(t: Float) = if (t >= LINE_END) 1.08f else maxOf(0.02f, (t / LINE_END) * 1.08f)

    fun flashAlpha(t: Float) = kf(t, 0f to 0f, 0.612f to 0f, 0.648f to 0.85f, 1.116f to 0f)

    fun ringAlpha(t: Float): Float {
        val u = (t - RING_START) / RING_LEN
        return if (u <= 0f || u >= 1f) 0f else 0.9f * (1f - u)
    }

    fun ringScale(t: Float): Float {
        val u = (t - RING_START) / RING_LEN
        return if (u <= 0f) 0.2f else 0.2f + 2.4f * decel(u)
    }

    fun logoAlpha(t: Float): Float = when {
        t < HIT -> 0f
        t < LOGO_IN_END -> decel((t - HIT) / (LOGO_IN_END - HIT))
        else -> kf(t, LOGO_IN_END to 1f, LOGO_OUT_START to 1f, DURATION to 0f)
    }

    fun logoScale(t: Float): Float = when {
        t < HIT -> 0.72f
        t < LOGO_IN_END -> 0.72f + 0.28f * decel((t - HIT) / (LOGO_IN_END - HIT))
        else -> kf(t, LOGO_IN_END to 1f, LOGO_OUT_START to 1.03f, DURATION to 1.09f)
    }

    /** Posición del reflejo de 0 (izquierda) a 1 (derecha); -1 si no se ve. */
    fun sweep(t: Float): Float =
        if (t < SWEEP_START || t > SWEEP_END) -1f else (t - SWEEP_START) / (SWEEP_END - SWEEP_START)
}
