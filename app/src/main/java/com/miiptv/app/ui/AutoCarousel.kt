package com.miiptv.app.ui

import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.MotionEvent
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.miiptv.app.api.ContentItem

/**
 * Carrusel horizontal de pósters que avanza de a una página completa y vuelve
 * al principio al llegar al final.
 *
 * ---------------------------------------------------------------------------
 * POR QUÉ CAMBIÓ
 *
 * Antes recibía [perPage] fijo desde afuera, y en el Inicio se estaba pasando
 * `perPage = 1`. Con un solo elemento por página, el ancho de la tarjeta salía
 * de dividir todo el ancho de la columna por uno: cada póster ocupaba la
 * columna entera. Y como item_poster.xml tiene `layout_height="match_parent"`,
 * también se estiraba a todo el alto. De ahí los dos rectángulos gigantes que
 * se comían la pantalla de Inicio.
 *
 * Ahora la cantidad por página NO se fija a mano: se calcula a partir del
 * espacio real que tiene el carrusel. La tarjeta toma el alto disponible, el
 * ancho sale de la proporción de un póster (2:3) y recién ahí se ve cuántas
 * entran. Así funciona solo en las dos disposiciones: en TV las dos columnas
 * van lado a lado y entran menos pósters por columna; en móvil van apiladas,
 * la columna es más ancha y entran más.
 * ---------------------------------------------------------------------------
 */
class AutoCarousel(
    private val recycler: RecyclerView,
    private val adapter: CarouselAdapter,
    private val delayMs: Long = 4500L
) {

    private val ui = Handler(Looper.getMainLooper())
    private val layoutManager =
        LinearLayoutManager(recycler.context, LinearLayoutManager.HORIZONTAL, false)

    /** Cuántos pósters entran por página. Se calcula al medir, no se fija a mano. */
    var perPage: Int = 1
        private set

    /** Lista completa recibida. Se recorta a páginas enteras recién al medir. */
    private var raw: List<ContentItem> = emptyList()

    private var page = 0
    private var running = false
    private var measureRetries = 0
    private var lastPerPage = 0
    private var lastCount = -1

    private val tick = object : Runnable {
        override fun run() {
            advance()
            ui.postDelayed(this, delayMs)
        }
    }

    fun attach() {
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        // setHasFixedSize(false): el tamaño del carrusel SÍ cambia cuando el
        // Inicio pasa de columnas lado a lado (TV) a apiladas (móvil).
        recycler.setHasFixedSize(false)

        // Si el usuario lo desliza a mano, se pausa y después retoma desde donde quedó
        recycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> stop()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        syncPage()
                        start()
                    }
                }
                return false
            }
        })

        // Recalcular cuando el carrusel cambia de tamaño (cambio de orientación,
        // de modo TV/móvil, o la primera vez que se hace visible). Solo cuando
        // el tamaño cambió de verdad: si no, se volvería a medir en bucle.
        recycler.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            val cambioAncho = (r - l) != (oldR - oldL)
            val cambioAlto = (b - t) != (oldB - oldT)
            if (cambioAncho || cambioAlto) measureItems()
        }
    }

    fun submit(items: List<ContentItem>) {
        raw = items
        page = 0
        measureRetries = 0
        lastPerPage = 0
        lastCount = -1
        measureItems()
    }

    val itemCount: Int get() = adapter.itemCount

    /**
     * Calcula el tamaño de la tarjeta a partir del espacio disponible.
     *
     * La tarjeta ocupa el alto del carrusel. Descontando el bloque de texto
     * (título + etiqueta) y el relleno, queda el alto del póster; el ancho sale
     * de la proporción 2:3. Con eso ya se sabe cuántas entran a lo ancho.
     */
    private fun measureItems() {
        val usable = recycler.width - recycler.paddingStart - recycler.paddingEnd
        val alto = recycler.height
        if (usable <= 0 || alto <= 0) {
            // La vista todavía no fue medida (o está oculta): reintentamos unas pocas veces
            if (measureRetries++ < 8) recycler.post { measureItems() }
            return
        }

        val dm = recycler.resources.displayMetrics
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)

        // Fila baja (móvil, con las dos secciones apiladas): título a una línea,
        // para no dejar al póster sin lugar.
        val compacto = alto < dp(COMPACT_BELOW_DP)
        adapter.compact = compacto
        val bloqueTexto = if (compacto) TEXT_BLOCK_COMPACT_DP else TEXT_BLOCK_DP

        val altoPoster = (alto - dp(bloqueTexto) - dp(CARD_PADDING_DP))
            .coerceAtLeast(dp(MIN_POSTER_DP))
        val anchoTarjeta = (altoPoster * POSTER_RATIO + dp(CARD_PADDING_DP))
            .coerceAtLeast(dp(MIN_CARD_DP))
        val margen = dp(ITEM_MARGIN_DP)

        perPage = (usable / (anchoTarjeta + margen)).toInt().coerceIn(1, MAX_PER_PAGE)
        adapter.itemWidth = (usable / perPage - margen).toInt().coerceAtLeast(dp(MIN_CARD_DP).toInt())
        applyList()
    }

    /** Recorta a páginas enteras para que nunca quede una fila a medias. */
    private fun applyList() {
        val recortada =
            if (raw.size < perPage) raw else raw.take((raw.size / perPage) * perPage)
        // Evita reenviar la misma lista y disparar otra vuelta de medición
        if (perPage == lastPerPage && recortada.size == lastCount) return
        lastPerPage = perPage
        lastCount = recortada.size
        adapter.submitList(recortada)
    }

    /**
     * @param startOffsetMs retraso extra del primer cambio. Sirve para que dos
     *        carruseles vecinos no cambien exactamente al mismo tiempo.
     */
    fun start(startOffsetMs: Long = 0L) {
        if (running || adapter.itemCount <= perPage) return
        running = true
        ui.postDelayed(tick, delayMs + startOffsetMs)
    }

    fun stop() {
        running = false
        ui.removeCallbacks(tick)
    }

    private fun advance() {
        val count = adapter.itemCount
        if (count <= perPage) return

        val next = page + perPage
        if (next >= count) {
            // Volver al principio: salto directo, para no hacer un barrido larguísimo hacia atrás
            page = 0
            layoutManager.scrollToPositionWithOffset(0, 0)
        } else {
            page = next
            smoothScrollToStart(page)
        }
    }

    /** Desplazamiento suave que deja el elemento destino pegado al borde izquierdo. */
    private fun smoothScrollToStart(position: Int) {
        val scroller = object : LinearSmoothScroller(recycler.context) {
            override fun getHorizontalSnapPreference() = SNAP_TO_START
            override fun getVerticalSnapPreference() = SNAP_TO_START
            override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics) =
                80f / displayMetrics.densityDpi
        }
        scroller.targetPosition = position
        layoutManager.startSmoothScroll(scroller)
    }

    private fun syncPage() {
        val first = layoutManager.findFirstVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION) {
            page = (first / perPage) * perPage
        }
    }

    private companion object {
        /** Margen horizontal total de cada tarjeta (ver item_poster.xml). */
        const val ITEM_MARGIN_DP = 10f

        /** Relleno vertical total de la tarjeta (8dp arriba + 8dp abajo). */
        const val CARD_PADDING_DP = 16f

        /** Alto del título (2 líneas) + la etiqueta de tipo, con sus márgenes. */
        const val TEXT_BLOCK_DP = 62f

        /** Lo mismo con el título a una sola línea. */
        const val TEXT_BLOCK_COMPACT_DP = 44f

        /** Por debajo de este alto de fila se usa el modo compacto. */
        const val COMPACT_BELOW_DP = 260f

        /** Proporción de un póster: el ancho es dos tercios del alto. */
        const val POSTER_RATIO = 2f / 3f

        const val MIN_POSTER_DP = 90f
        const val MIN_CARD_DP = 96f

        /** Tope por las dudas: más de esto y los pósters quedan ilegibles. */
        const val MAX_PER_PAGE = 6
    }
}
