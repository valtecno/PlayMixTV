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

    private val tick = object : Runnable {
        override fun run() {
            if (items.size > 1 && !b.heroBanner.hasFocus()) {
                index = (index + 1) % items.size
                render(animate = true)
            }
            ui.postDelayed(this, DELAY_MS)
        }
    }

    init {
        b.btnHeroPlay.setOnClickListener { items.getOrNull(index)?.let(onPlay) }
        b.btnHeroInfo.setOnClickListener { items.getOrNull(index)?.let(onInfo) }
        listOf(b.btnHeroPlay, b.btnHeroInfo).forEach { boton ->
            boton.setOnFocusChangeListener { v, tieneFoco ->
                v.animate().scaleX(if (tieneFoco) 1.08f else 1f)
                    .scaleY(if (tieneFoco) 1.08f else 1f).setDuration(120).start()
            }
        }
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

    fun start() {
        ui.removeCallbacks(tick)
        if (items.size > 1) ui.postDelayed(tick, DELAY_MS)
    }

    fun stop() = ui.removeCallbacks(tick)

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
        Picasso.get().load(item.icon).config(android.graphics.Bitmap.Config.RGB_565)
            .resize(600, 0).onlyScaleDown().into(b.ivHeroPoster)
        Picasso.get().load(item.icon).config(android.graphics.Bitmap.Config.RGB_565)
            .resize(600, 0).onlyScaleDown().into(b.ivHeroBackdrop)

        if (animate) {
            b.heroContent.alpha = 0f
            b.heroContent.animate().alpha(1f).setDuration(350).start()
        } else {
            b.heroContent.alpha = 1f
        }
    }

    private companion object {
        const val DELAY_MS = 7000L
        const val MAX = 6
    }
}
