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
 *
 * ---------------------------------------------------------------------------
 * POR QUÉ NO SE ESCALA NADA
 *
 * El resalte de foco es **solo color**. Nada crece, nada se levanta.
 *
 * La versión anterior agrandaba la vista enfocada (entre 3% y 18% según el
 * caso) buscando que se leyera de lejos. El problema es que `scaleX/scaleY`
 * agranda el DIBUJO pero no el hueco que la vista ocupa en el layout, y de ahí
 * salían tres fallas a la vez:
 *
 *  1. El padre recorta lo que se sale (`clipChildren` viene en true), así que
 *     el borde crecido quedaba cortado en seco.
 *  2. Lo que no se recortaba se salía de la pantalla: en el menú superior el
 *     último ítem se iba por el borde derecho, y en las filas de Cuenta la fila
 *     enfocada se comía los márgenes de los dos lados.
 *  3. Escalar interpola los píxeles ya dibujados, así que el texto de la vista
 *     enfocada se veía borroso — justo la que hay que poder leer.
 *
 * Además se acumulaba: una fila podía recibir escala de `Appearance.applyLevel`
 * y otra más del recorrido de esta clase, y terminaba creciendo el doble de lo
 * previsto.
 *
 * El color, en cambio, no ocupa espacio. Los `StateListDrawable` de
 * [Appearance] ya distinguen el estado enfocado con degradado y anillo blanco,
 * que se ve perfectamente desde el sillón y no puede desbordar nada.
 *
 * **Si en el futuro hace falta más presencia**, la respuesta NO es volver a
 * escalar: es subir el contraste del estado enfocado en [Appearance], o —si de
 * verdad hiciera falta que crezca— poner `android:clipChildren="false"` en el
 * contenedor y reservar el margen en el layout. Escalar sin eso vuelve a traer
 * los tres problemas de arriba.
 * ---------------------------------------------------------------------------
 */
object RemoteControl {

    /** ¿Se está manejando con control remoto? */
    fun isEnabled(c: Context): Boolean = !DeviceMode.isMobile(c)

    /**
     * Prepara una tarjeta de lista (canal, película, serie, emisora) para que se
     * vea cuál tiene el foco.
     *
     * El resalte es **solo color**: el fondo cambia de estado y nada más. Ver
     * la nota "POR QUÉ NO SE ESCALA NADA" arriba.
     *
     * @param remoto si es false solo se deja el fondo con estados (inofensivo:
     *        en modo táctil las vistas no reciben foco) y no se toca nada más.
     */
    fun applyItemFocus(
        view: View,
        remoto: Boolean,
        cornerRadiusDp: Float = 14f
    ) {
        view.background = Appearance.cardFocusBackground(view.context, cornerRadiusDp)

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

        // Sin listener de foco: el StateListDrawable del fondo ya hace todo el
        // trabajo, y lo hace sin código y sin animación que limpiar al reciclar.
        view.onFocusChangeListener = null
    }

    /**
     * Resalta un botón de icono del reproductor.
     *
     * Se distingue de [applyItemFocus] en la fuerza: acá el relleno es OPACO y
     * el anillo más grueso, porque estos botones flotan sobre el video. Con un
     * resalte suave, sobre una escena clara no se ve nada.
     *
     * Conserva el fondo que el botón ya tuviera para el estado en reposo, así
     * que los de zapping no pierden su círculo naranja.
     *
     * @param circular false para botones con forma de pastilla o rectángulo.
     */
    fun applyIconFocus(
        view: View,
        remoto: Boolean,
        circular: Boolean = true,
        cornerRadiusDp: Float = 12f
    ) {
        if (!remoto) return
        view.background =
            Appearance.iconFocusBackground(view.context, view.background, circular, cornerRadiusDp)
    }

    /**
     * Igual que [applyIconFocus] pero recorriendo una jerarquía entera.
     *
     * Es para los controles centrales del reproductor (retroceder, reproducir,
     * adelantar): los infla Media3 dentro del PlayerView, así que buscarlos por
     * id obligaría a depender de los identificadores internos de la librería,
     * que pueden cambiar de versión. Recorriendo el árbol se resaltan solos.
     *
     * Solo toca ImageView e ImageButton: así la barra de tiempo, que también se
     * puede enfocar, conserva su aspecto propio en vez de recibir un círculo.
     */
    fun applyIconFocusToTree(root: View, remoto: Boolean) {
        if (!remoto) return
        if (root is android.widget.ImageView && root.isClickable && root.isFocusable) {
            applyIconFocus(root, true)
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) applyIconFocusToTree(root.getChildAt(i), true)
        }
    }

    /**
     * Recorre una pantalla entera y resalta todo lo que se pueda enfocar.
     *
     * Existe para las pantallas armadas con filas repetidas en XML —Cuenta y
     * Personalizar—, donde el fondo viene de un `style` compartido sin estado
     * enfocado. Ir vista por vista sería una lista larga de ids que además
     * habría que acordarse de ampliar cada vez que se agrega una fila; así el
     * resalte lo hereda cualquier fila nueva sin tocar nada.
     *
     * Las filas que son contenedores además bloquean el foco de sus hijos: sin
     * eso el interruptor de adentro se lo roba y con las flechas hay que pasar
     * dos veces por cada fila. La fila entera ya alterna el interruptor al
     * pulsarla, así que no se pierde nada.
     */
    fun applyFocusToTree(root: View, remoto: Boolean, cornerRadiusDp: Float = 14f) {
        if (!remoto) return

        if (root.isClickable && root.isFocusable) {
            /*
             * Filas que se pueden enfocar pero no hacen nada.
             *
             * El estilo SettingsRow marca focusable y clickable para TODAS las
             * filas, incluidas las que solo muestran un dato (el servidor, el
             * usuario). Con el remoto eso obligaba a pasar por ellas sin que
             * pasara nada, y ahora encima se iluminarían prometiendo una acción
             * que no existe. Si no tienen a quién avisar, se sacan del recorrido.
             */
            if (!root.hasOnClickListeners()) {
                root.isFocusable = false
                root.isClickable = false
            } else {
                if (root is ViewGroup) {
                    // Fila completa: lavado suave sobre el vidrio que ya tenía.
                    //
                    // Solo el fondo. Estas filas ocupan el ancho de la pantalla,
                    // así que hasta un 3% de escala eran ~20 px por lado que se
                    // salían del margen y quedaban pegados al borde, con el
                    // texto interpolado. Es lo que se ve en la pantalla de
                    // Cuenta cuando el remoto se para sobre "Buffer".
                    root.background =
                        Appearance.cardFocusBackground(root.context, cornerRadiusDp, root.background)
                    // El interruptor de adentro no debe robarse el foco: la fila
                    // entera ya lo alterna al pulsarla.
                    root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    return
                }
                // Botón suelto: relleno opaco con anillo, que es más rotundo
                applyIconFocus(root, true, circular = root is android.widget.ImageView, cornerRadiusDp = 12f)
                return
            }
        }

        if (root is ViewGroup) {
            for (i in 0 until root.childCount) applyFocusToTree(root.getChildAt(i), remoto, cornerRadiusDp)
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
