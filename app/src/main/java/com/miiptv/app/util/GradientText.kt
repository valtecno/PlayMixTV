package com.miiptv.app.util

import android.graphics.LinearGradient
import android.graphics.Shader
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.miiptv.app.R

/**
 * Pinta el texto de un TextView con el degradado naranja→rosa→violeta
 * de la marca PlayMix TV (el mismo del logo). Se usa para títulos
 * destacados, como el nombre de la app en la barra superior.
 */
fun TextView.applyBrandGradient() {
    val palette = Appearance.palette(context)
    val orange = palette.start
    val pink = palette.end
    val purple = ContextCompat.getColor(context, R.color.brand_purple)

    viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (width > 0) {
                paint.shader = LinearGradient(
                    0f, 0f, width.toFloat(), 0f,
                    intArrayOf(orange, pink, purple),
                    null,
                    Shader.TileMode.CLAMP
                )
                invalidate()
                viewTreeObserver.removeOnPreDrawListener(this)
            }
            return true
        }
    })
}
