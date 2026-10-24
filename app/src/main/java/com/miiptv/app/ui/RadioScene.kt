package com.miiptv.app.ui

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.palette.graphics.Palette
import com.miiptv.app.R
import com.miiptv.app.databinding.ActivityPlayerBinding
import com.miiptv.app.util.DevicePerf

/**
 * Pantalla de radio "Aurora en vivo": fondo de luces difusas con el color de
 * la emisora, logo con anillos que laten, onda inferior y el reloj grande.
 *
 * Rendimiento:
 *  - Las manchas de la aurora son degradados radiales que solo se DESPLAZAN
 *    (translationX/Y), no se redibujan ni se desenfocan en tiempo real.
 *  - Todo corre solo mientras suena ([start]/[stop]): al pausar o salir de la
 *    pantalla se detiene.
 *  - En modo equipo débil no se anima nada: queda el mismo diseño, quieto.
 */
class RadioScene(private val b: ActivityPlayerBinding) {

    private val ctx = b.root.context
    private val res = ctx.resources
    private val gamaBaja = DevicePerf.esGamaBaja(ctx)
    private val animaciones = mutableListOf<Animator>()

    private val rosa = ContextCompat.getColor(ctx, R.color.brand_primary)
    private val naranja = ContextCompat.getColor(ctx, R.color.brand_accent)
    private val violeta = ContextCompat.getColor(ctx, R.color.brand_purple)

    /** Color principal actual (el de la emisora, o el rosa de marca). */
    private var principal = rosa
    private var paletaPedida = 0

    private val encajador = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        b.radioColumns.post { encajarColumnas() }
    }

    fun setup() {
        // Los anillos laten por fuera de su caja y las columnas pueden achicarse
        // con escala: nada de eso debe recortarse.
        listOf<ViewGroup>(b.radioColumns, b.colRadioStation, b.colRadioClock, b.radioLogoBox)
            .forEach { it.clipChildren = false; it.clipToPadding = false }

        // Termómetro a la altura del texto grande
        b.tvRadioTemp.compoundDrawablesRelative[0]?.let {
            val lado = dp(30)
            it.mutate().setBounds(0, 0, lado * 2 / 3, lado)
            b.tvRadioTemp.setCompoundDrawablesRelative(it, null, null, null)
        }

        aplicarColores(rosa)
        aplicarOrientacion()
        b.radioColumns.removeOnLayoutChangeListener(encajador)
        b.radioColumns.addOnLayoutChangeListener(encajador)
    }

    // ---------------- Colores ----------------

    /**
     * Saca el color más vivo del logo de la emisora. Si el logo es gris,
     * blanco o negro (muy común), se queda con el rosa de la marca.
     */
    fun colorDesdeLogo(bitmap: Bitmap?) {
        val pedido = ++paletaPedida
        if (bitmap == null || bitmap.isRecycled) { aplicarColores(rosa); return }
        runCatching {
            Palette.from(bitmap).maximumColorCount(12).generate { p ->
                if (pedido != paletaPedida) return@generate   // ya cambió de emisora
                val muestra = p?.vibrantSwatch ?: p?.lightVibrantSwatch ?: p?.dominantSwatch
                val hsl = muestra?.hsl
                val util = hsl != null && hsl[1] >= 0.30f && hsl[2] in 0.20f..0.80f
                aplicarColores(if (util) muestra!!.rgb else rosa)
            }
        }.onFailure { aplicarColores(rosa) }
    }

    fun colorPorDefecto() {
        paletaPedida++
        aplicarColores(rosa)
    }

    private fun aplicarColores(color: Int) {
        principal = color
        b.aurora1.background = radial(color, 0x8C, dp(280))
        b.aurora2.background = radial(naranja, 0x70, dp(260))
        b.aurora3.background = radial(violeta, 0x80, dp(230))
        b.radioLogoBox.background = radial(color, 0x66, dp(95))
        b.colRadioClock.background = radial(violeta, 0x45, dp(220))
        b.radioRing1.background = anillo(naranja, 0xA0)
        b.radioRing2.background = anillo(color, 0x5A)
        b.tvRadioNowLabel.setTextColor(color)
        b.radioWave.setColors(color, naranja, violeta)
    }

    private fun radial(color: Int, alfa: Int, radioPx: Int) = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = radioPx.toFloat()
        colors = intArrayOf(conAlfa(color, alfa), conAlfa(color, alfa / 3), Color.TRANSPARENT)
    }

    private fun anillo(color: Int, alfa: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(dp(2), conAlfa(color, alfa))
    }

    private fun conAlfa(color: Int, alfa: Int) = (color and 0x00FFFFFF) or (alfa.coerceIn(0, 255) shl 24)

    // ---------------- Distribución ----------------

    /**
     * Horizontal (TV, teléfono acostado): emisora | reloj.
     * Vertical (teléfono parado): emisora arriba, reloj abajo.
     */
    fun aplicarOrientacion() {
        val vertical = res.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        b.radioColumns.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        listOf(b.colRadioStation, b.colRadioClock).forEach { col ->
            val lp = col.layoutParams as LinearLayout.LayoutParams
            if (vertical) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = 0
            } else {
                lp.width = 0
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            lp.weight = 1f
            col.layoutParams = lp
            col.setGravity(Gravity.CENTER)
        }

        val sep = b.radioDivider.layoutParams as LinearLayout.LayoutParams
        if (vertical) {
            sep.width = dp(160); sep.height = dp(1)
            sep.setMargins(0, dp(10), 0, dp(10))
        } else {
            sep.width = dp(1); sep.height = dp(260)
            sep.setMargins(dp(8), 0, dp(8), 0)
        }
        b.radioDivider.layoutParams = sep

        b.tvRadioClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (vertical) 84f else 104f)
    }

    /**
     * Si una columna no cabe en el alto disponible (teléfono acostado, pantallas
     * chicas), se achica entera con escala en vez de cortarse. En una TV no
     * hace nada: hay espacio de sobra.
     */
    private fun encajarColumnas() {
        listOf(b.colRadioStation, b.colRadioClock).forEach { col ->
            val alto = col.height
            val ancho = col.width
            if (alto <= 0 || ancho <= 0) return@forEach
            col.measure(
                View.MeasureSpec.makeMeasureSpec(ancho, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val natural = col.measuredHeight
            val disponible = if (b.radioColumns.orientation == LinearLayout.VERTICAL) alto
                else b.radioColumns.height - b.radioColumns.paddingTop - b.radioColumns.paddingBottom
            val escala = if (natural > disponible && natural > 0)
                (disponible.toFloat() / natural).coerceIn(0.55f, 1f) else 1f
            col.pivotX = ancho / 2f
            col.pivotY = alto / 2f
            col.scaleX = escala
            col.scaleY = escala
        }
    }

    // ---------------- "Sonando ahora" ----------------

    /** Muestra artista/canción si la emisora los envía; si no, oculta la tarjeta. */
    fun mostrarCancion(texto: String?) {
        val limpio = texto?.trim().orEmpty()
        if (limpio.length < 2) {
            b.radioNowCard.visibility = View.GONE
            return
        }
        if (b.tvRadioSong.text?.toString() != limpio) b.tvRadioSong.text = limpio
        b.tvRadioSong.isSelected = true   // marquesina si no entra
        b.radioNowCard.visibility = View.VISIBLE
    }

    // ---------------- Animaciones ----------------

    fun start() {
        if (gamaBaja || animaciones.isNotEmpty()) return
        val d = res.displayMetrics.density

        // Aurora: cada mancha va y viene muy lento, con su propio ritmo
        animaciones += deriva(b.aurora1, 70f * d, 45f * d, 23_000L)
        animaciones += deriva(b.aurora2, -60f * d, -50f * d, 29_000L)
        animaciones += deriva(b.aurora3, -50f * d, 60f * d, 33_000L)

        // Anillos: un latido suave, el exterior desfasado
        animaciones += latido(b.radioRing1, 1.07f, 1f, 0.55f, 0L)
        animaciones += latido(b.radioRing2, 1.10f, 0.65f, 0.15f, 350L)

        b.radioWave.start()
        animaciones.forEach { it.start() }
    }

    fun stop() {
        animaciones.forEach { it.cancel() }
        animaciones.clear()
        b.radioWave.stop()
        listOf(b.radioRing1, b.radioRing2).forEach { it.scaleX = 1f; it.scaleY = 1f; it.alpha = 1f }
    }

    private fun deriva(v: View, dx: Float, dy: Float, ms: Long): Animator =
        ObjectAnimator.ofPropertyValuesHolder(
            v,
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, v.translationX, dx),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, v.translationY, dy)
        ).apply {
            duration = ms
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }

    private fun latido(v: View, escala: Float, alfaDesde: Float, alfaHasta: Float, retraso: Long): Animator =
        ObjectAnimator.ofPropertyValuesHolder(
            v,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, escala),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, escala),
            PropertyValuesHolder.ofFloat(View.ALPHA, alfaDesde, alfaHasta)
        ).apply {
            duration = 1400L
            startDelay = retraso
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }

    private fun dp(v: Int): Int = (v * res.displayMetrics.density).toInt()
}
