package com.miiptv.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.miiptv.app.R

/**
 * Dibuja el logo y, encima, un reflejo de luz que lo cruza. El reflejo solo se
 * pinta sobre los píxeles del logo (modo SRC_ATOP), así no ilumina el fondo.
 */
class IntroLogoView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var logo: Bitmap? = null
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }

    /** 0 = reflejo a la izquierda, 1 = a la derecha, negativo = sin reflejo. */
    var sweep: Float = -1f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        logo?.recycle()
        logo = null
        if (w <= 0 || h <= 0) return
        val src = BitmapFactory.decodeResource(resources, R.drawable.logo_playmix) ?: return
        logo = if (src.width == w && src.height == h) src
        else Bitmap.createScaledBitmap(src, w, h, true).also { if (it !== src) src.recycle() }
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = logo ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val saved = canvas.saveLayer(0f, 0f, w, h, null)
        canvas.drawBitmap(bmp, 0f, 0f, logoPaint)
        if (sweep >= 0f) {
            val cx = -0.25f * w + sweep * 1.5f * w
            sweepPaint.shader = LinearGradient(
                cx - 0.2f * w, 0.15f * h, cx + 0.2f * w, -0.15f * h,
                intArrayOf(Color.TRANSPARENT, Color.argb(242, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, sweepPaint)
        }
        canvas.restoreToCount(saved)
    }
}
