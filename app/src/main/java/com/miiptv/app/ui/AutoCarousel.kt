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
 * Carrusel horizontal que muestra exactamente [perPage] elementos ocupando todo
 * el ancho disponible, y que avanza de a una página completa (no de a un ítem).
 * Al llegar al final vuelve al principio.
 */
class AutoCarousel(
    private val recycler: RecyclerView,
    private val adapter: CarouselAdapter,
    private val perPage: Int = 4,
    private val delayMs: Long = 4500L
) {

    private val ui = Handler(Looper.getMainLooper())
    private val layoutManager =
        LinearLayoutManager(recycler.context, LinearLayoutManager.HORIZONTAL, false)

    private var page = 0
    private var running = false
    private var measureRetries = 0

    private val tick = object : Runnable {
        override fun run() {
            advance()
            ui.postDelayed(this, delayMs)
        }
    }

    fun attach() {
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.setHasFixedSize(true)

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
    }

    /** Carga los datos recortados a páginas completas, para que nunca quede una fila a medias. */
    fun submit(items: List<ContentItem>) {
        val fullPages = items.take((items.size / perPage) * perPage)
        adapter.submitList(fullPages)
        page = 0
        measureRetries = 0
        measureItems()
    }

    val itemCount: Int get() = adapter.itemCount

    /** Reparte el ancho disponible entre [perPage] elementos. */
    private fun measureItems() {
        val usable = recycler.width - recycler.paddingStart - recycler.paddingEnd
        if (usable <= 0) {
            // La vista todavía no fue medida (o está oculta): reintentamos unas pocas veces
            if (measureRetries++ < 6) recycler.post { measureItems() }
            return
        }
        val marginPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, ITEM_MARGIN_DP, recycler.resources.displayMetrics
        ).toInt()
        adapter.itemWidth = (usable / perPage) - marginPx
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
    }
}
