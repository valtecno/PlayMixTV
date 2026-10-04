package com.miiptv.app.util

/**
 * Cuentas del selector de perfiles en móvil, sin depender de Android para
 * poder probarlas: 2 columnas centradas y círculos que se encogen cuando hay
 * muchos perfiles, de modo que todos se vean completos.
 */
object ProfileLayout {

    const val COLUMNS = 2
    const val MAX_DP = 130f
    const val MIN_DP = 52f

    /** Diámetro del círculo en píxeles para [itemCount] ítems (perfiles + "+"). */
    fun avatarSizePx(itemCount: Int, widthPx: Int, heightPx: Int, density: Float): Int {
        val rows = ((itemCount + COLUMNS - 1) / COLUMNS).coerceAtLeast(1)
        val availH = heightPx - 300 * density              // cabecera + márgenes
        val byHeight = availH / rows - 64 * density        // nombre + padding por fila
        val byWidth = (widthPx - 48 * density) / COLUMNS - 24 * density
        return minOf(MAX_DP * density, byHeight, byWidth)
            .coerceAtLeast(MIN_DP * density).toInt()
    }

    /** Columnas que ocupa un ítem: el último, si queda solo en su fila, ocupa las dos (centrado). */
    fun spanSize(position: Int, itemCount: Int): Int =
        if (position == itemCount - 1 && position % COLUMNS == 0) COLUMNS else 1
}
