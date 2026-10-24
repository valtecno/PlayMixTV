package com.miiptv.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Contenedor de un solo hijo que nunca lo recorta: lo mide a su alto natural
 * y, si no cabe en el espacio disponible, lo achica entero (escala) y lo
 * centra.
 *
 * Existe por la pantalla de radio: en algunos televisores (y en teléfonos
 * acostados) el alto útil es menor que la columna de la emisora. Un
 * LinearLayout normal le da a los últimos hijos el espacio que sobra, que
 * puede ser cero, y los botones quedaban cortados por la mitad.
 */
class FitScaleLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hijo = getChildAt(0)
        if (hijo == null || hijo.visibility == View.GONE) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val ancho = MeasureSpec.getSize(widthMeasureSpec)
        val anchoHijo = (ancho - paddingLeft - paddingRight).coerceAtLeast(0)
        hijo.measure(
            MeasureSpec.makeMeasureSpec(anchoHijo, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val natural = hijo.measuredHeight + paddingTop + paddingBottom
        val alto = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(natural, MeasureSpec.getSize(heightMeasureSpec))
            else -> natural
        }
        setMeasuredDimension(ancho, alto)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val hijo = getChildAt(0) ?: return
        if (hijo.visibility == View.GONE) return
        val w = right - left
        val h = bottom - top
        val cw = hijo.measuredWidth
        val ch = hijo.measuredHeight
        val disponible = (h - paddingTop - paddingBottom).coerceAtLeast(1)

        // El hijo se ubica a tamaño natural y centrado (puede sobresalir)...
        val x = paddingLeft + (w - paddingLeft - paddingRight - cw) / 2
        val y = paddingTop + (disponible - ch) / 2
        hijo.layout(x, y, x + cw, y + ch)

        // ...y si sobresale se achica alrededor de su centro hasta caber.
        val escala = if (ch > disponible) disponible.toFloat() / ch else 1f
        hijo.pivotX = cw / 2f
        hijo.pivotY = ch / 2f
        hijo.scaleX = escala
        hijo.scaleY = escala
    }
}
