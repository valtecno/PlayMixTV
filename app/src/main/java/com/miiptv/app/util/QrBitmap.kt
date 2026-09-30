package com.miiptv.app.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Genera un [Bitmap] cuadrado con un código QR para la [url] dada.
 * Se usa en modo TV, donde abrir WhatsApp directamente no es posible:
 * el usuario escanea el QR con su celular.
 */
object QrBitmap {

    fun generate(url: String, sizePx: Int = 512): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,        // margen mínimo (silently: 4 celdas)
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
