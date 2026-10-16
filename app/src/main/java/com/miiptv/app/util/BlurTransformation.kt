package com.miiptv.app.util

import android.graphics.Bitmap
import com.squareup.picasso.Transformation

/**
 * Desenfoque barato para el fondo de las fichas: se reduce la carátula a unos
 * pocos píxeles y se le pasa un promedio de vecinos (dos pasadas). Al estirarse
 * después a pantalla completa queda un fondo suave y borroso. Sin RenderScript
 * (obsoleto) y sin librerías: sobre un bitmap de ~48 px es instantáneo.
 */
class BlurTransformation(private val ancho: Int = 48, private val radio: Int = 3) : Transformation {

    override fun transform(source: Bitmap): Bitmap {
        val alto = (ancho.toFloat() * source.height / source.width).toInt().coerceAtLeast(1)
        val chico = Bitmap.createScaledBitmap(source, ancho, alto, true)
        if (chico !== source) source.recycle()
        val px = IntArray(ancho * alto)
        chico.getPixels(px, 0, ancho, 0, 0, ancho, alto)
        repeat(2) { cajaHorizontal(px, ancho, alto); cajaVertical(px, ancho, alto) }
        val salida = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
        salida.setPixels(px, 0, ancho, 0, 0, ancho, alto)
        chico.recycle()
        return salida
    }

    override fun key(): String = "blur-$ancho-$radio"

    private fun cajaHorizontal(px: IntArray, w: Int, h: Int) {
        val fila = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) fila[x] = promedio(px, w, h, x, y, horizontal = true)
            System.arraycopy(fila, 0, px, y * w, w)
        }
    }

    private fun cajaVertical(px: IntArray, w: Int, h: Int) {
        val col = IntArray(h)
        for (x in 0 until w) {
            for (y in 0 until h) col[y] = promedio(px, w, h, x, y, horizontal = false)
            for (y in 0 until h) px[y * w + x] = col[y]
        }
    }

    private fun promedio(px: IntArray, w: Int, h: Int, x: Int, y: Int, horizontal: Boolean): Int {
        var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
        for (d in -radio..radio) {
            val xx = if (horizontal) (x + d).coerceIn(0, w - 1) else x
            val yy = if (horizontal) y else (y + d).coerceIn(0, h - 1)
            val c = px[yy * w + xx]
            a += c ushr 24; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            n++
        }
        return ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
    }
}
