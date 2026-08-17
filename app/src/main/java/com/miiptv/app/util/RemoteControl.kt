package com.miiptv.app.util

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Manejo del **control remoto** (D-pad).
 *
 * ---------------------------------------------------------------------------
 * QUÉ PROBLEMA RESUELVE
 *
 * La app siempre fue "navegable" con el remoto en el sentido técnico: las
 * vistas tenían `focusable="true"`, así que al pulsar una flecha el foco de
 * Android se movía. Lo que faltaba era que ESO SE VIERA. Los fondos se
 * asignaban con un color plano, sin estado enfocado, y `selectableItemBackground`
 * sobre una tarjeta de vidrio oscuro es prácticamente invisible en un televisor.
 * Resultado: el usuario pulsaba flechas y no sabía dónde estaba parado.
 *
 * ---------------------------------------------------------------------------
 * CUÁNDO SE CONSIDERA ACTIVO
 *
 * Se deriva del modo elegido en la primera pantalla, no de un ajuste aparte:
 *
 *  - **TV elegido**  → activo, y queda guardado en el dispositivo (lo persiste
 *    [DeviceMode.set], así que sobrevive a cerrar la app y a reiniciar).
 *  - **Móvil elegido** → inactivo: se usa el dedo.
 *  - **Todavía no eligió** → se decide por hardware ([DeviceMode.suggest]), de
 *    modo que en un televisor el remoto funciona **desde el primer arranque**,
 *    antes de haber elegido nada.
 *
 * La pantalla de elección es la excepción: ahí el foco SIEMPRE se resalta, sin
 * consultar nada. Es la única pantalla donde equivocarse deja al usuario
 * encerrado — si el aparato no tiene pantalla táctil y tampoco se ve el foco,
 * no hay forma de elegir "TV" y salir de ahí. Pasa de verdad con los decos
 * baratos, que no siempre se declaran como televisores.
 * ---------------------------------------------------------------------------
 */
object RemoteControl {

    /** ¿Se está manejando con control remoto? */
    fun isEnabled(c: Context): Boolean = !DeviceMode.isMobile(c)

    /**
     * Prepara una tarjeta de lista (canal, película, serie, emisora) para que se
     * vea cuál tiene el foco.
     *
     * @param remoto si es false solo se deja el fondo con estados (inofensivo:
     *        en modo táctil las vistas no reciben foco) y no se toca nada más.
     * @param escalaFoco cuánto crece la tarjeta enfocada. Un póster de grilla
     *        aguanta 1.05 sin tocar a sus vecinos; una fila a lo ancho de la
     *        pantalla, no: ese 5% son decenas de píxeles que se salen del
     *        recuadro. Por eso las filas usan un valor mucho más chico.
     */
    fun applyItemFocus(
        view: View,
        remoto: Boolean,
        cornerRadiusDp: Float = 14f,
        escalaFoco: Float = 1.05f
    ) {
        view.background = Appearance.cardFocusBackground(view.context, cornerRadiusDp)

        // Las vistas se reciclan: si una quedó agrandada de cuando tenía el
        // foco, hay que devolverla a su tamaño antes de reutilizarla.
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.elevation = 0f

        if (!remoto) {
            view.onFocusChangeListener = null
            (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            return
        }

        /*
         * En TV el foco tiene que saltar de tarjeta a tarjeta, no meterse
         * adentro. Sin esto, la estrella de favoritos (que es clickable, o sea
         * enfocable) se roba el foco y navegar la grilla con las flechas se
         * vuelve un laberinto: derecha te lleva a la estrella de la misma
         * tarjeta en vez de a la película de al lado.
         *
         * Quien llame a esto debe ofrecer otra forma de marcar favoritos; el
         * adapter usa pulsación larga del botón central.
         */
        (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        view.isFocusable = true

        view.setOnFocusChangeListener { v, tieneFoco ->
            // El color ya lo cambia el StateListDrawable del fondo. Esto agrega
            // el relieve: un poco más grande y por encima de las vecinas, que es
            // lo que hace que se lea de lejos, sentado en un sillón.
            val escala = if (tieneFoco) escalaFoco else 1f
            v.animate().scaleX(escala).scaleY(escala).setDuration(140).start()
            v.elevation = if (tieneFoco) 8f * v.resources.displayMetrics.density else 0f
        }
    }

    /**
     * Pide el foco cuando la vista ya esté puesta en pantalla.
     *
     * `requestFocus()` a secas dentro de `onCreate` no hace nada: todavía no hay
     * layout y la vista no puede recibir foco. Por eso se difiere con `post`.
     */
    fun focusWhenReady(view: View?) {
        view ?: return
        view.post {
            if (view.isAttachedToWindow) view.requestFocus()
        }
    }
}
