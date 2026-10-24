package com.miiptv.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * Tres ondas finas que ondulan sobre la barra de la radio.
 *
 * Es una vista baja (unos 56 dp) que se redibuja sola mientras [start] está
 * activo; con [stop] queda quieta en su último dibujo. En equipos débiles
 * RadioScene nunca la arranca: se ve la onda, pero inmóvil.
 */
class RadioWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Onda(val amp: Float, val largo: Float, val fase: Float, val velocidad: Float)

    private val ondas = listOf(
        Onda(amp = 0.30f, largo = 0.42f, fase = 0f, velocidad = 1f),
        Onda(amp = 0.22f, largo = 0.31f, fase = 1.6f, velocidad = -0.7f),
        Onda(amp = 0.16f, largo = 0.55f, fase = 3.1f, velocidad = 0.5f)
    )

    private val pinceles = List(3) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = resources.displayMetrics.density * (if (it == 0) 2f else 1.5f)
        }
    }
    private val camino = Path()
    private var t = 0f
    private var animador: ValueAnimator? = null

    init {
        setColors(0xFFFF3CAC.toInt(), 0xFFFF6A00.toInt(), 0xFF7B2FF7.toInt())
    }

    /** Colores de las tres ondas (se les baja la opacidad para que no compitan). */
    fun setColors(a: Int, b: Int, c: Int) {
        val alfas = intArrayOf(0x99, 0x80, 0x8C)
        listOf(a, b, c).forEachIndexed { i, color ->
            pinceles[i].color = (color and 0x00FFFFFF) or (alfas[i] shl 24)
        }
        invalidate()
    }

    fun start() {
        if (animador != null) return
        animador = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
            duration = 6000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animador?.cancel()
        animador = null
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val centro = h / 2f
        // Un punto cada ~6 px alcanza para que la curva se vea suave.
        val paso = (6f * resources.displayMetrics.density / 2f).coerceAtLeast(4f)
        ondas.forEachIndexed { i, o ->
            camino.reset()
            var x = 0f
            val k = (2 * PI / (w * o.largo)).toFloat()
            while (x <= w + paso) {
                // La amplitud varía a lo ancho para que no parezca una sinusoide perfecta
                val envolvente = 0.6f + 0.4f * sin(x / w * 2.6f * PI.toFloat() + o.fase)
                val y = centro + sin(x * k + o.fase + t * o.velocidad) * h * o.amp * envolvente
                if (x == 0f) camino.moveTo(x, y) else camino.lineTo(x, y)
                x += paso
            }
            canvas.drawPath(camino, pinceles[i])
        }
    }
}
