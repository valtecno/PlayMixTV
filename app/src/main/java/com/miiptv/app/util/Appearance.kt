package com.miiptv.app.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue

/**
 * Preferencias de apariencia de la app (sección "Personalizar").
 *
 * El tema por defecto es el de la marca PlayMix TV (naranja → rosa); los demás
 * son alternativas opcionales que el usuario puede elegir.
 */
object Appearance {

    private const val PREFS = "miiptv_appearance"

    /** Paleta de acento: nombre visible + color inicial y final del degradado. */
    data class Palette(val name: String, val start: Int, val end: Int)

    val palettes = listOf(
        Palette("PlayMix", 0xFFFF6A00.toInt(), 0xFFFF3CAC.toInt()),
        Palette("Azul", 0xFF2E8BE6.toInt(), 0xFF14C7E8.toInt()),
        Palette("Rojo", 0xFFE0413F.toInt(), 0xFFF2705E.toInt()),
        Palette("Verde", 0xFF1FA85C.toInt(), 0xFF4BD97F.toInt()),
        Palette("Violeta", 0xFF7B2FF7.toInt(), 0xFFB06AF9.toInt()),
        Palette("Dorado", 0xFFF2A619.toInt(), 0xFFFFD24D.toInt()),
        Palette("Rosa", 0xFFFF4E8B.toInt(), 0xFFFF8FB8.toInt()),
        Palette("Cian", 0xFF12B5CC.toInt(), 0xFF56E1F0.toInt())
    )

    // ---- Al tocar una película ----
    const val CLICK_PLAY = 0        // ir directo al reproductor
    const val CLICK_DETAILS = 1     // abrir la ficha con reparto, director, etc.

    /** Opciones de densidad de la grilla (columnas). */
    private val gridOptionsTv = listOf(3, 4, 5, 6)
    private val gridOptionsMobile = listOf(2, 3, 4, 5)

    /** Opciones de densidad de grilla, acordes al tamaño real de la pantalla. */
    fun gridOptions(c: Context): List<Int> =
        if (DeviceMode.isMobile(c)) gridOptionsMobile else gridOptionsTv

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- Tema de color ----------------

    fun getPaletteIndex(c: Context): Int =
        prefs(c).getInt("palette", 0).coerceIn(0, palettes.lastIndex)

    fun setPaletteIndex(c: Context, index: Int) =
        prefs(c).edit().putInt("palette", index.coerceIn(0, palettes.lastIndex)).apply()

    fun palette(c: Context): Palette = palettes[getPaletteIndex(c)]

    /** Color de acento sólido (para textos e íconos resaltados). */
    fun accent(c: Context): Int = palette(c).start

    /**
     * Los botones de la app tienen **tres niveles de jerarquía**, para que se
     * distinga de un vistazo qué es cada cosa. Todos usan los colores del logo:
     *
     *  - [Level.PRIMARY]: la sección abierta del menú principal. Degradado
     *    naranja→rosa completo. Es el elemento más fuerte de la pantalla.
     *  - [Level.SELECTED]: la opción elegida *dentro* de esa sección (categoría,
     *    filtro, país). Color plano y más profundo, sin degradado: se lee como
     *    "esto está activo" sin competir con el menú.
     *  - [Level.INACTIVE]: el resto de opciones disponibles. Vidrio apagado con
     *    borde sutil y texto atenuado.
     */
    enum class Level { PRIMARY, SELECTED, INACTIVE }

    /** Mezcla un color con negro para obtener un tono más profundo del mismo. */
    private fun deepen(color: Int, factor: Float): Int = android.graphics.Color.argb(
        android.graphics.Color.alpha(color),
        (android.graphics.Color.red(color) * factor).toInt(),
        (android.graphics.Color.green(color) * factor).toInt(),
        (android.graphics.Color.blue(color) * factor).toInt()
    )

    /** Aplica el nivel de jerarquía a un botón o chip. */
    fun applyLevel(
        view: android.widget.TextView,
        level: Level,
        cornerRadiusDp: Float = 20f,
        tintIcon: Boolean = true
    ) {
        val c = view.context
        val p = palette(c)
        val radio = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, cornerRadiusDp, c.resources.displayMetrics
        )
        val borde = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1.4f, c.resources.displayMetrics
        ).toInt()

        val fondo: GradientDrawable
        val textoColor: Int
        val alpha: Float

        when (level) {
            Level.PRIMARY -> {
                fondo = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(p.start, p.end)
                )
                textoColor = androidx.core.content.ContextCompat.getColor(
                    c, com.miiptv.app.R.color.text_light
                )
                alpha = 1f
            }

            Level.SELECTED -> {
                // Tono profundo del mismo color, plano y con borde vivo
                fondo = GradientDrawable().apply {
                    setColor(deepen(p.end, 0.45f))
                    setStroke(borde, p.start)
                }
                textoColor = androidx.core.content.ContextCompat.getColor(
                    c, com.miiptv.app.R.color.text_light
                )
                alpha = 1f
            }

            Level.INACTIVE -> {
                fondo = GradientDrawable().apply {
                    setColor(
                        androidx.core.content.ContextCompat.getColor(
                            c, com.miiptv.app.R.color.glass_card
                        )
                    )
                    setStroke(
                        borde / 2,
                        androidx.core.content.ContextCompat.getColor(
                            c, com.miiptv.app.R.color.glass_border
                        )
                    )
                }
                textoColor = androidx.core.content.ContextCompat.getColor(
                    c, com.miiptv.app.R.color.text_muted
                )
                alpha = 0.9f
            }
        }

        fondo.shape = GradientDrawable.RECTANGLE
        fondo.cornerRadius = radio

        /*
         * El fondo ya no es un drawable suelto sino un StateListDrawable con un
         * estado de foco.
         *
         * Antes, con el control remoto no se veía nada: moverse por los chips de
         * categoría, por los países de Radios o por el menú superior no cambiaba
         * un solo pixel, porque acá se asignaba un color plano sin estados. En
         * pantalla táctil daba igual (en modo táctil las vistas no reciben foco),
         * pero en TV dejaba al usuario a ciegas.
         *
         * El estado enfocado se distingue de los otros dos niveles por el ANILLO
         * blanco: "seleccionado" es color profundo sin anillo, "sección abierta"
         * es degradado sin anillo, y "acá está el control remoto" es degradado
         * CON anillo.
         */
        view.background = withFocusState(view.context, fondo, cornerRadiusDp)

        // Con el foco encima, el texto atenuado de los chips inactivos queda
        // flojo sobre el degradado: en ese estado pasa a blanco.
        view.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(
                    androidx.core.content.ContextCompat.getColor(
                        c, com.miiptv.app.R.color.text_light
                    ),
                    textoColor
                )
            )
        )
        view.alpha = alpha

        /*
         * Los ítems del menú superior llevan ícono a la izquierda. El color del
         * texto ahora cambia con el foco, pero el tinte de un drawable no sigue
         * a un ColorStateList por su cuenta: sin esto, un ítem enfocado quedaba
         * con el texto en blanco y el ícono en lavanda apagado, como a medio
         * pintar. Se retiñe cuando entra o sale el foco.
         *
         * ACÁ NO SE ESCALA NADA. Antes el ítem enfocado crecía un 10%, y eso
         * rompía la barra: `scaleX/scaleY` agranda el dibujo pero NO el hueco
         * que la vista ocupa en el layout, así que el ítem crecido se salía de
         * su sitio, el padre lo recortaba (clipChildren viene en true) y el
         * texto quedaba interpolado, o sea borroso. El color ya dice dónde está
         * el foco; el tamaño no hacía falta.
         *
         * tintIcon = false (YouTube): este listener es justamente lo que
         * rompía el logo. El teñido inicial de paintNavItem() se podía saltar,
         * pero acá se retiñe de nuevo cada vez que el foco entra O sale --
         * entonces con el ícono ya rojo+blanco de fábrica, apenas el control
         * remoto lo tocaba una vez quedaba pintado de un solo color para
         * siempre, sin importar lo que hiciera paintNavItem() antes.
         */
        if (tintIcon) {
            view.setOnFocusChangeListener { v, _ ->
                val tv = v as android.widget.TextView
                tv.compoundDrawablesRelative.forEach { it?.mutate()?.setTint(tv.currentTextColor) }
            }
        }
    }

    // ---------------- Foco del control remoto ----------------

    /** Mismo color con otra transparencia (0..255). */
    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun px(c: Context, dp: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp, c.resources.displayMetrics
    )

    /**
     * Relleno del elemento sobre el que está parado el control remoto.
     *
     * Es un **lavado difuminado** del color de acento, no un bloque plano: el
     * degradado va de opaco en una esquina a casi transparente en la otra, así
     * que la carátula o el logo del canal siguen viéndose por debajo en vez de
     * quedar tapados. El borde sólido es lo que define el recuadro.
     */
    fun focusFill(c: Context, cornerRadiusDp: Float): GradientDrawable {
        val p = palette(c)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                withAlpha(p.start, 0xD0),
                withAlpha(p.end, 0x7A),
                withAlpha(p.end, 0x2E)
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = px(c, cornerRadiusDp)
            setStroke(px(c, 2.5f).toInt(), p.start)
        }
    }

    /** Degradado pleno con anillo blanco: el foco sobre chips y botones. */
    private fun focusRing(c: Context, cornerRadiusDp: Float): GradientDrawable {
        val p = palette(c)
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(p.start, p.end)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = px(c, cornerRadiusDp)
            setStroke(px(c, 2.5f).toInt(), withAlpha(0xFFFFFF, 0xE0))
        }
    }

    /** Envuelve un fondo cualquiera para que gane estado enfocado. */
    fun withFocusState(c: Context, normal: Drawable, cornerRadiusDp: Float): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focusRing(c, cornerRadiusDp))
            addState(intArrayOf(), normal)
        }

    /**
     * Fondo para un botón de icono del reproductor cuando tiene el foco.
     *
     * Acá el resalte es MUCHO más marcado que en las listas, y es a propósito:
     * los botones del reproductor flotan sobre el video, que puede ser de
     * cualquier color y estar en movimiento. Un lavado translúcido como el de
     * las tarjetas se pierde sobre una escena clara. Por eso va relleno opaco
     * del color de acento más un anillo blanco: se ve sobre cualquier imagen.
     *
     * @param normal lo que el botón ya tenía de fondo, que se conserva para el
     *        estado en reposo. Así los botones de zapping no pierden su círculo
     *        naranja ni los demás su fondo transparente.
     */
    fun iconFocusBackground(
        c: Context,
        normal: Drawable?,
        circular: Boolean = true,
        cornerRadiusDp: Float = 12f
    ): StateListDrawable {
        val p = palette(c)
        val enfocado = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(p.start, p.end)
        ).apply {
            shape = if (circular) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            if (!circular) cornerRadius = px(c, cornerRadiusDp)
            setStroke(px(c, 3f).toInt(), withAlpha(0xFFFFFF, 0xF0))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), enfocado)
            if (normal != null) addState(intArrayOf(), normal)
        }
    }

    /**
     * Fondo completo para una tarjeta de la lista (canal, película, serie,
     * emisora): vidrio cuando está en reposo, lavado difuminado del acento
     * cuando el control remoto está encima.
     */
    fun cardFocusBackground(
        c: Context,
        cornerRadiusDp: Float = 14f,
        /**
         * Fondo a conservar para el estado en reposo. Si es null se usa el
         * vidrio de siempre. Lo usan las filas de Cuenta y Personalizar, que ya
         * traen su propio fondo desde el estilo del XML y no deberían cambiar
         * de aspecto solo por ganar un estado enfocado.
         */
        normal: Drawable? = null
    ): StateListDrawable {
        val reposo = normal ?: GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = px(c, cornerRadiusDp)
            setColor(
                androidx.core.content.ContextCompat.getColor(
                    c, com.miiptv.app.R.color.glass_card
                )
            )
            setStroke(
                px(c, 0.6f).toInt(),
                androidx.core.content.ContextCompat.getColor(
                    c, com.miiptv.app.R.color.glass_border
                )
            )
        }
        val enfocado = focusFill(c, cornerRadiusDp)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), enfocado)
            // state_selected para poder marcar el ítem que se está previsualizando
            addState(intArrayOf(android.R.attr.state_selected), enfocado)
            addState(intArrayOf(), reposo)
        }
    }

    /**
     * Atajo para chips de dentro de una sección: seleccionado o no.
     * Nunca usa PRIMARY, que queda reservado al menú principal.
     */
    fun applyChipState(view: android.widget.TextView, active: Boolean, cornerRadiusDp: Float = 20f) {
        applyLevel(view, if (active) Level.SELECTED else Level.INACTIVE, cornerRadiusDp)
    }

    /** Degradado de marca listo para usar como fondo. */
    fun gradient(c: Context, cornerRadiusDp: Float): GradientDrawable {
        val p = palette(c)
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(p.start, p.end)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, cornerRadiusDp, c.resources.displayMetrics
            )
        }
    }

    /** Mismo degradado de marca que [gradient], pero circular (para íconos redondos). */
    fun gradientOval(c: Context): GradientDrawable {
        val p = palette(c)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(p.start, p.end)
        ).apply {
            shape = GradientDrawable.OVAL
        }
    }

    // ---------------- Subtítulos ----------------

    fun getSubtitleSize(c: Context): Int = prefs(c).getInt("subtitle_size", 20).coerceIn(12, 44)

    fun setSubtitleSize(c: Context, sp: Int) =
        prefs(c).edit().putInt("subtitle_size", sp.coerceIn(12, 44)).apply()

    // ---------------- Al tocar una película ----------------

    fun getMovieClick(c: Context): Int = prefs(c).getInt("movie_click", CLICK_DETAILS)

    fun setMovieClick(c: Context, mode: Int) = prefs(c).edit().putInt("movie_click", mode).apply()

    // ---------------- Densidad de las grillas ----------------

    /**
     * Columnas con las que arranca la grilla de Películas y Series.
     *
     * En TV son 6, el máximo de [gridOptionsTv]: en una pantalla de 40 pulgadas
     * o más, 4 carátulas quedaban enormes y obligaban a desplazarse mucho para
     * recorrer un catálogo. A esa distancia el título se lee igual.
     *
     * En móvil siguen siendo 2: ahí 4 columnas dejan las carátulas ilegibles.
     *
     * Es solo el valor inicial. Quien ya haya elegido su densidad en
     * Personalizar conserva la suya, porque esto es el `default` de la
     * preferencia guardada y no la pisa.
     */
    private fun defaultColumns(c: Context) = if (DeviceMode.isMobile(c)) 2 else 6

    fun getMoviesColumns(c: Context): Int =
        clampColumns(c, prefs(c).getInt("movies_cols", defaultColumns(c)))
    fun setMoviesColumns(c: Context, cols: Int) = prefs(c).edit().putInt("movies_cols", cols).apply()

    fun getSeriesColumns(c: Context): Int =
        clampColumns(c, prefs(c).getInt("series_cols", defaultColumns(c)))

    /**
     * Si el usuario había elegido 6 columnas en TV y después pasa a móvil, ese
     * valor ya no existe entre las opciones: se ajusta a la más cercana válida.
     */
    private fun clampColumns(c: Context, value: Int): Int {
        val opciones = gridOptions(c)
        return if (value in opciones) value else opciones.minByOrNull { kotlin.math.abs(it - value) }!!
    }
    fun setSeriesColumns(c: Context, cols: Int) = prefs(c).edit().putInt("series_cols", cols).apply()

    fun columnsLabel(c: Context, cols: Int): String {
        val opciones = gridOptions(c)
        return when (opciones.indexOf(cols)) {
            0 -> "Más cómodo"
            1 -> "Balanceado"
            2 -> "Más pósters"
            else -> "Ultra compacto"
        }
    }
}
