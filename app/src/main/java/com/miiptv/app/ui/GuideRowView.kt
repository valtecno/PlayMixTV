package com.miiptv.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.miiptv.app.api.EpgListing
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.Epg

/**
 * Una fila de la Guía: dibuja los programas de un canal como bloques cuyo
 * ancho es proporcional a su duración dentro de la ventana de tiempo
 * [inicioSeg, finSeg] (epoch, segundos). El programa en el aire se tiñe con el
 * color de acento y una línea marca "ahora".
 */
class GuideRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val bloque = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = d; color = 0x3DFFFFFF
    }
    private val linea = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * d }
    private val texto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }
    private val vacio = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }
    private val rect = RectF()

    private var programas: List<EpgListing> = emptyList()
    private var inicioSeg = 0L
    private var finSeg = 1L
    private var cargando = true

    fun setVentana(inicio: Long, fin: Long) {
        inicioSeg = inicio
        finSeg = fin.coerceAtLeast(inicio + 1)
        invalidate()
    }

    /** null = todavía cargando; lista (posiblemente vacía) = ya respondió el panel. */
    fun setProgramas(lista: List<EpgListing>?) {
        cargando = lista == null
        programas = lista.orEmpty()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val ahora = System.currentTimeMillis() / 1000
        val acento = Appearance.accent(context)
        val margen = 2 * d
        val dur = (finSeg - inicioSeg).toFloat()

        val visibles = programas.filter {
            it.startTimestamp > 0 && it.stopTimestamp > inicioSeg && it.startTimestamp < finSeg
        }
        if (visibles.isEmpty()) {
            val msg = if (cargando) "…" else "Sin programación"
            canvas.drawText(msg, 8 * d, h / 2 + vacio.textSize / 3, vacio)
        }

        for (p in visibles) {
            val ini = maxOf(p.startTimestamp, inicioSeg)
            val fin = minOf(if (p.stopTimestamp > 0) p.stopTimestamp else finSeg, finSeg)
            val x1 = (ini - inicioSeg) / dur * w + margen
            val x2 = (fin - inicioSeg) / dur * w - margen
            if (x2 - x1 < 4 * d) continue
            rect.set(x1, margen, x2, h - margen)
            val enElAire = ahora >= p.startTimestamp && (p.stopTimestamp <= 0 || ahora < p.stopTimestamp)
            bloque.color = if (enElAire) (acento and 0x00FFFFFF) or 0x66000000 else 0x26FFFFFF
            canvas.drawRoundRect(rect, 8 * d, 8 * d, bloque)
            canvas.drawRoundRect(rect, 8 * d, 8 * d, borde)

            val titulo = Epg.titulo(p) ?: continue
            val disponible = x2 - x1 - 12 * d
            if (disponible < 20 * d) continue
            val recortado = TextUtils.ellipsize(titulo, texto, disponible, TextUtils.TruncateAt.END)
            canvas.drawText(recortado, 0, recortado.length, x1 + 6 * d, h / 2 + texto.textSize / 3, texto)
        }

        if (ahora in inicioSeg..finSeg) {
            val x = (ahora - inicioSeg) / dur * w
            linea.color = acento
            canvas.drawLine(x, 0f, x, h, linea)
        }
    }
}
