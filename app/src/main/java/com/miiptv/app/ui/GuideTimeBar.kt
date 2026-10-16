package com.miiptv.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Regla de horas de la Guía: una marca "HH:mm" cada 30 minutos, en la hora del aparato. */
class GuideTimeBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val texto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB8AED1.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }
    private val marca = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3DFFFFFF; strokeWidth = d }
    private val formato = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }

    private var inicioSeg = 0L
    private var finSeg = 1L

    fun setVentana(inicio: Long, fin: Long) {
        inicioSeg = inicio
        finSeg = fin.coerceAtLeast(inicio + 1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val dur = (finSeg - inicioSeg).toFloat()
        var t = inicioSeg
        while (t < finSeg) {
            val x = (t - inicioSeg) / dur * w
            canvas.drawLine(x, h * 0.55f, x, h, marca)
            canvas.drawText(formato.format(Date(t * 1000)), x + 4 * d, h * 0.62f - 2 * d, texto)
            t += 1800
        }
    }
}
