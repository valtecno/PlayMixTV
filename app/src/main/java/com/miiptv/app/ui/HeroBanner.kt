package com.miiptv.app.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import com.miiptv.app.R
import com.miiptv.app.api.ContentItem
import com.miiptv.app.api.ContentType
import com.miiptv.app.databinding.ActivityMainBinding
import com.miiptv.app.util.Appearance
import com.squareup.picasso.Picasso

/**
 * Banner destacado del Inicio: una carátula grande con título y botones que
 * rota sola entre lo último agregado. Solo muestra ítems que traen carátula.
 *
 * - "Ver ahora" reproduce (películas) o abre la serie.
 * - "Más info" abre la ficha de la película. En series el botón se oculta,
 *   porque abrir la serie ya es abrir su ficha.
 *
 * Con control remoto la rotación se pausa mientras un botón tiene el foco.
 */
class HeroBanner(
    private val b: ActivityMainBinding,
    private val onPlay: (ContentItem) -> Unit,
    private val onInfo: (ContentItem) -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private var items: List<ContentItem> = emptyList()
    private var index = 0
    private var movil = false

    /** Equipo débil: rotación más lenta y sin fundido (menos decodificación y dibujo). */
    private val gamaBaja = com.miiptv.app.util.DevicePerf.esGamaBaja(b.root.context)
    private val delayMs get() = if (gamaBaja) 12_000L else DELAY_MS

    private val tick = object : Runnable {
        override fun run() {
            if (items.size > 1 && !b.heroBanner.hasFocus()) {
                index = (index + 1) % items.size
                render(animate = true)
            }
            ui.postDelayed(this, delayMs)
        }
    }

    init {
        b.btnHeroPlay.setOnClickListener { items.getOrNull(index)?.let(onPlay) }
        b.btnHeroInfo.setOnClickListener { items.getOrNull(index)?.let(onInfo) }
        // Foco del control remoto: solo color (anillo blanco en el fondo con estados).
        // Nada de escalar: deformaba el botón y desbordaba el panel de vidrio.
    }

    /** Ítems con carátula, películas y series alternadas, hasta [MAX]. */
    fun submit(peliculas: List<ContentItem>, series: List<ContentItem>) {
        val conCaratula = { l: List<ContentItem> -> l.filter { !it.icon.isNullOrBlank() } }
        val p = conCaratula(peliculas)
        val s = conCaratula(series)
        val mezcla = ArrayList<ContentItem>()
        var i = 0
        while (mezcla.size < MAX && (i < p.size || i < s.size)) {
            if (i < p.size) mezcla.add(p[i])
            if (mezcla.size < MAX && i < s.size) mezcla.add(s[i])
            i++
        }
        // Mismo contenido que antes: no se reinicia la rotación ni se parpadea.
        if (mezcla.map { it.type to it.id } == items.map { it.type to it.id } &&
            b.heroBanner.visibility == View.VISIBLE
        ) return
        items = mezcla
        index = 0
        if (items.isEmpty()) {
            stop()
            b.heroBanner.visibility = View.GONE
        } else {
            b.heroBanner.visibility = View.VISIBLE
            render(animate = false)
        }
    }

    val hasItems: Boolean get() = items.isNotEmpty()

    /**
     * Móvil: la carátula llena el banner de borde a borde (sin la carátula
     * chica al costado) y el texto se apoya abajo sobre un velo oscuro.
     * TV: el banner de siempre, con carátula al costado.
     */
    fun setMovil(esMovil: Boolean) {
        if (movil == esMovil) return
        movil = esMovil
        aplicarModo()
        if (items.isNotEmpty()) render(animate = false)
    }

    /** La carátula en miniatura a la derecha solo va en TV. */
    private fun miniatura() = !com.miiptv.app.util.DeviceMode.isMobile(b.root.context)

    private fun aplicarModo() {
        val d = b.root.resources.displayMetrics.density
        if (movil) {
            b.heroBanner.background = null
            b.heroBanner.clipToOutline = false
            b.ivHeroBackdrop.alpha = 0.55f   // más transparente: deja ver el fondo violeta
            b.heroScrim.setBackgroundResource(
                if (miniatura()) R.drawable.bg_hero_scrim_glass_tv else R.drawable.bg_hero_scrim_glass_mobile
            )
            // Carátula completa en miniatura a la derecha, acorde a la pantalla:
            // alto ~45 % de la pantalla, sin pasar del 38 % del ancho (relación 2:3).
            val dm = b.root.resources.displayMetrics
            val alto = minOf(dm.heightPixels * 0.45f, dm.widthPixels * 0.38f * 1.5f).toInt()
            val lp = b.ivHeroPoster.layoutParams as android.widget.LinearLayout.LayoutParams
            lp.height = alto
            lp.width = (alto * 2f / 3f).toInt()
            lp.gravity = android.view.Gravity.BOTTOM
            b.ivHeroPoster.layoutParams = lp
            b.ivHeroPoster.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            // En móvil no hay espacio: solo se muestra en TV.
            b.ivHeroPoster.visibility = if (miniatura()) View.VISIBLE else View.GONE
            b.ivHeroPoster.setBackgroundResource(R.drawable.bg_hero_poster)
            b.ivHeroPoster.clipToOutline = true
            b.heroContent.gravity = android.view.Gravity.BOTTOM
            b.heroContent.setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (32 * d).toInt())
            b.tvHeroTitle.textSize = 26f
        } else {
            b.heroBanner.setBackgroundResource(R.drawable.bg_chip_outline)
            b.heroBanner.clipToOutline = true
            b.ivHeroBackdrop.alpha = 0.35f
            b.heroScrim.setBackgroundResource(R.drawable.bg_hero_scrim)
            b.ivHeroPoster.visibility = View.VISIBLE
            b.heroContent.gravity = android.view.Gravity.CENTER_VERTICAL
            b.heroContent.setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
            b.tvHeroTitle.textSize = 22f
        }
    }

    fun start() {
        ui.removeCallbacks(tick)
        if (items.size > 1) ui.postDelayed(tick, delayMs)
    }

    fun stop() {
        ui.removeCallbacks(tick)
        b.ivHeroBackdrop.animate().cancel()
    }

    fun hide() {
        stop()
        items = emptyList()
        b.heroBanner.visibility = View.GONE
    }

    private fun render(animate: Boolean) {
        val item = items.getOrNull(index) ?: return
        val ctx = b.root.context
        val esSerie = item.type == ContentType.SERIES

        b.tvHeroTag.setText(if (esSerie) R.string.hero_tag_series else R.string.hero_tag_movie)
        b.tvHeroTag.setTextColor(Appearance.accent(ctx))
        b.tvHeroTitle.text = item.name
        b.btnHeroPlay.setText(if (esSerie) R.string.hero_open_series else R.string.hero_play)
        b.btnHeroInfo.visibility = if (esSerie) View.GONE else View.VISIBLE
        b.tvHeroDots.text = if (items.size > 1) {
            items.indices.joinToString(" ") { if (it == index) "●" else "○" }
        } else ""

        Picasso.get().cancelRequest(b.ivHeroPoster)
        Picasso.get().cancelRequest(b.ivHeroBackdrop)
        if (miniatura()) {
            Picasso.get().load(item.icon).config(android.graphics.Bitmap.Config.RGB_565)
                .resize(600, 0).onlyScaleDown().into(b.ivHeroPoster)
        }
        Picasso.get().load(item.icon).config(android.graphics.Bitmap.Config.RGB_565)
            .resize(if (movil) 1000 else 600, 0).onlyScaleDown().into(b.ivHeroBackdrop)

        if (animate && !gamaBaja) {
            // Entrada: el panel de vidrio sube suavemente mientras aparece.
            val d = b.root.resources.displayMetrics.density
            b.heroContent.alpha = 0f
            b.heroContent.translationY = 18f * d
            b.heroContent.animate().alpha(1f).translationY(0f).setDuration(500)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            // Zoom lento de la imagen (efecto cine) durante lo que dura la diapositiva.
            b.ivHeroBackdrop.animate().cancel()
            b.ivHeroBackdrop.scaleX = 1f
            b.ivHeroBackdrop.scaleY = 1f
            b.ivHeroBackdrop.animate().scaleX(1.07f).scaleY(1.07f).setDuration(delayMs + 1500)
                .setInterpolator(android.view.animation.LinearInterpolator()).start()
        } else {
            b.heroContent.alpha = 1f
            b.heroContent.translationY = 0f
        }
    }

    private companion object {
        const val DELAY_MS = 7000L
        const val MAX = 6
    }
}
