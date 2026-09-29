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
 * Carrusel del Inicio: una carátula por columna, cambiando de a una.
 *
 * ---------------------------------------------------------------------------
 * QUÉ ESTABA MAL Y QUÉ SE CORRIGIÓ
 *
 * Mostrar un póster por página es la decisión de diseño correcta. Lo que estaba
 * mal era cómo se calculaba el tamaño: el ancho de la tarjeta salía de dividir
 * todo el ancho de la columna por la cantidad por página, o sea la columna
 * entera. Sumado a `layout_height="match_parent"` en item_poster.xml, cada
 * tarjeta se estiraba a lo ancho Y a lo alto, y de ahí salían los dos
 * rectángulos gigantes que se comían la pantalla de Inicio.
 *
 * Ahora la tarjeta ocupa TODA la sección: una carátula por vez, a pantalla de
 * columna. La proporción se respeta dentro de la tarjeta, con fitCenter en la
 * imagen (ver item_poster.xml), así que se aprovecha todo el espacio sin
 * deformar ni recortar la carátula.
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

    /** Una carátula por página: el carrusel avanza de a una. */
    private val perPage = 1

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
        // El tamaño del carrusel SÍ cambia: el Inicio pasa de columnas lado a
        // lado (TV) a apiladas (móvil), y eso cambia el alto de cada fila.
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

        // Recalcular cuando el carrusel cambia de tamaño (primera medición,
        // rotación, cambio de modo TV/móvil). Solo si el tamaño cambió de
        // verdad, o se volvería a medir en bucle.
        recycler.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if ((r - l) != (oldR - oldL) || (b - t) != (oldB - oldT)) measureItems()
        }
    }

    /**
     * La lista se entrega al adapter en el acto, de forma síncrona.
     *
     * Esto es obligatorio: showHome() consulta itemCount en la línea siguiente
     * para decidir si oculta la columna. Si la carga se difiere hasta que la
     * vista esté medida, en ese momento todavía vale 0, la columna se oculta y,
     * al quedar con alto 0, la medición ya no puede completarse nunca: el
     * Inicio queda vacío para siempre. La medición solo ajusta el tamaño de la
     * tarjeta y puede llegar después sin problema.
     */
    fun submit(items: List<ContentItem>) {
        page = 0
        measureRetries = 0
        adapter.submitList(items)
        measureItems()
    }

    val itemCount: Int get() = adapter.itemCount

    /** La tarjeta ocupa el recuadro completo: se ve una carátula por vez. */
    private fun measureItems() {
        val usable = recycler.width - recycler.paddingStart - recycler.paddingEnd
        val alto = recycler.height
        if (usable <= 0 || alto <= 0) {
            // La vista todavía no fue medida (o está oculta): reintentamos unas pocas veces
            if (measureRetries++ < 8) recycler.post { measureItems() }
            return
        }

        val margen = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, ITEM_MARGIN_DP, recycler.resources.displayMetrics
        ).toInt()

        // Fila baja: título a una línea, para no dejar a la carátula sin lugar.
        adapter.compact = alto < TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, COMPACT_BELOW_DP, recycler.resources.displayMetrics
        )

        // Ancho completo menos los márgenes de la tarjeta: entra exactamente una,
        // y con clipToPadding=true la siguiente queda fuera de vista hasta que
        // el carrusel avanza.
        adapter.itemWidth = (usable - margen).coerceAtLeast(1)
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

    /** Desplazamiento suave que deja el elemento destino pegado al borde inicial. */
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

        /** Por debajo de este alto de fila se usa el modo compacto. */
        const val COMPACT_BELOW_DP = 260f
    }
}
