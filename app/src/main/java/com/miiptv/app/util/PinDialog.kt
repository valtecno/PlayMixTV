package com.miiptv.app.util

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.miiptv.app.R
import com.miiptv.app.databinding.DialogPinBinding
import com.miiptv.app.databinding.DialogPinRecoverBinding

/**
 * Teclado numérico de PIN, a pantalla completa, con el mismo estilo visual
 * del resto de la app (tarjetas "vidrio", degradado de marca, dorado como
 * color activo). Reemplaza el diálogo viejo con EditText + teclado del sistema.
 */
object PinDialog {

    private const val PIN_LENGTH = 4

    /**
     * Pide el PIN existente. Llama a onSuccess si es correcto.
     *
     * El botón de abajo es "Recuperar PIN" (en vez de "Cancelar": para salir
     * alcanza con Atrás). Si se ingresa el PIN maestro que da soporte por
     * WhatsApp, se borra el PIN guardado, se pide crear uno nuevo y, creado
     * ese, se sigue con lo que se estaba haciendo (onSuccess).
     */
    fun ask(context: Context, onSuccess: () -> Unit) {
        showKeypad(
            context,
            title = context.getString(R.string.pin_ask_title),
            subtitle = context.getString(R.string.pin_ask_subtitle),
            secondaryLabel = context.getString(R.string.pin_recover),
            onSecondary = { showRecover(context) }
        ) { pin, dialog, reset ->
            when {
                // Primero el PIN propio: si alguien eligió justo el mismo
                // número que el maestro, que funcione como su PIN normal.
                Parental.checkPin(context, pin) -> {
                    dialog.dismiss()
                    onSuccess()
                }
                Parental.isMasterPin(pin) -> {
                    dialog.dismiss()
                    Parental.removePin(context)
                    Toast.makeText(context, R.string.pin_reset_done, Toast.LENGTH_LONG).show()
                    create(context, onSuccess)
                }
                else -> {
                    Toast.makeText(context, context.getString(R.string.pin_wrong), Toast.LENGTH_SHORT).show()
                    reset()
                }
            }
        }
    }

    /**
     * Cómo recuperar un PIN olvidado: escribir a soporte por WhatsApp, que
     * responde con el PIN maestro. En celular, un botón abre WhatsApp con el
     * mensaje ya escrito; en TV (donde WhatsApp no suele estar) se muestra un
     * código QR para escanear con el celular.
     *
     * El QR es una imagen fija (img_qr_recuperar_pin) con el mismo enlace y
     * mensaje que el botón. Si se cambia el número o el texto
     * pin_recover_whatsapp_message, hay que regenerar también esa imagen.
     */
    private fun showRecover(context: Context) {
        val vista = DialogPinRecoverBinding.inflate(LayoutInflater.from(context))
        val dialog = AlertDialog.Builder(context).setView(vista.root).create()
        val tv = RemoteControl.isEnabled(context)

        vista.ivPinRecoverQr.visibility = if (tv) View.VISIBLE else View.GONE
        vista.tvPinRecoverQrHint.visibility = if (tv) View.VISIBLE else View.GONE
        vista.btnPinRecoverWhatsapp.visibility = if (tv) View.GONE else View.VISIBLE

        vista.btnPinRecoverWhatsapp.setOnClickListener {
            val mensaje = context.getString(R.string.pin_recover_whatsapp_message)
            val url = "https://wa.me/${HelpContent.WHATSAPP_SOPORTE}?text=" + Uri.encode(mensaje)
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                dialog.dismiss()
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, R.string.contact_no_whatsapp, Toast.LENGTH_LONG).show()
            }
        }
        vista.btnPinRecoverClose.setOnClickListener { dialog.dismiss() }

        vista.btnPinRecoverWhatsapp.background = Appearance.withFocusState(
            context, vista.btnPinRecoverWhatsapp.background!!, 12f
        )
        vista.btnPinRecoverClose.background = Appearance.withFocusState(
            context, vista.btnPinRecoverClose.background!!, 12f
        )

        dialog.show()
        if (tv) RemoteControl.focusWhenReady(vista.btnPinRecoverClose)
    }

    /** Crea (o cambia) el PIN. Se pide dos veces para confirmarlo. */
    fun create(context: Context, onSuccess: () -> Unit) {
        showKeypad(
            context,
            title = context.getString(R.string.pin_create_title),
            subtitle = context.getString(R.string.pin_create_subtitle)
        ) { firstPin, dialog, _ ->
            dialog.dismiss()
            showKeypad(
                context,
                title = context.getString(R.string.pin_confirm_title),
                subtitle = context.getString(R.string.pin_confirm_subtitle)
            ) { secondPin, dialog2, reset2 ->
                if (secondPin == firstPin) {
                    Parental.setPin(context, secondPin)
                    dialog2.dismiss()
                    Toast.makeText(context, context.getString(R.string.pin_saved), Toast.LENGTH_SHORT).show()
                    onSuccess()
                } else {
                    Toast.makeText(context, context.getString(R.string.pin_mismatch), Toast.LENGTH_SHORT).show()
                    reset2()
                }
            }
        }
    }

    /**
     * Vuelve al inicio de la app. CLEAR_TOP descarta lo que haya quedado
     * encima de la pantalla principal en vez de apilar otra copia.
     */
    private fun goHome(context: Context) {
        val intent = Intent(context, com.miiptv.app.ui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Si el contexto no es una Activity hace falta tarea nueva, o el sistema rechaza el intent
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        if (context is Activity && context !is com.miiptv.app.ui.MainActivity) context.finish()
    }

    /**
     * Arma y muestra el teclado. [onComplete] se llama al juntar los 4 dígitos;
     * recibe el PIN escrito, el propio diálogo (para cerrarlo si está bien) y
     * una función [reset] para limpiar los puntos si hay que reintentar.
     */
    private fun showKeypad(
        context: Context,
        title: String,
        subtitle: String,
        secondaryLabel: String = context.getString(R.string.cancel),
        onSecondary: ((Dialog) -> Unit)? = null,
        onComplete: (pin: String, dialog: Dialog, reset: () -> Unit) -> Unit
    ) {
        val binding = DialogPinBinding.inflate(LayoutInflater.from(context))
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(binding.root)
        dialog.setCancelable(true)

        binding.pinIconBg.background = Appearance.gradientOval(context)
        binding.tvPinTitle.text = title
        binding.tvPinSubtitle.text = subtitle

        val dots = listOf(binding.pinDot0, binding.pinDot1, binding.pinDot2, binding.pinDot3)
        val entered = StringBuilder()

        fun refreshDots() {
            dots.forEachIndexed { i, dot ->
                dot.setBackgroundResource(
                    if (i < entered.length) R.drawable.bg_pin_dot_filled else R.drawable.bg_pin_dot_empty
                )
            }
        }

        fun reset() {
            entered.clear()
            refreshDots()
        }

        fun addDigit(d: String) {
            if (entered.length >= PIN_LENGTH) return
            entered.append(d)
            refreshDots()
            if (entered.length == PIN_LENGTH) {
                onComplete(entered.toString(), dialog, ::reset)
            }
        }

        val keys: List<Pair<TextView, String>> = listOf(
            binding.pinKey0 to "0", binding.pinKey1 to "1", binding.pinKey2 to "2",
            binding.pinKey3 to "3", binding.pinKey4 to "4", binding.pinKey5 to "5",
            binding.pinKey6 to "6", binding.pinKey7 to "7", binding.pinKey8 to "8",
            binding.pinKey9 to "9"
        )
        keys.forEach { (view, digit) -> view.setOnClickListener { addDigit(digit) } }

        binding.pinKeyBackspace.setOnClickListener {
            if (entered.isNotEmpty()) {
                entered.deleteCharAt(entered.length - 1)
                refreshDots()
            }
        }

        // El botón de abajo: "Cancelar" por defecto, o lo que pida quien llama
        // (en ask(), "Recuperar PIN"). Sin acción propia, cierra el teclado.
        binding.tvPinCancel.text = secondaryLabel
        binding.tvPinCancel.setOnClickListener {
            if (onSecondary != null) onSecondary(dialog) else dialog.dismiss()
        }

        // Botón de inicio, con el mismo tratamiento que el del menú principal:
        // degradado pleno y el ícono teñido del color del texto.
        Appearance.applyLevel(binding.btnPinHome, Appearance.Level.PRIMARY, 22f)
        binding.btnPinHome.compoundDrawablesRelative.forEach {
            it?.mutate()?.setTint(binding.btnPinHome.currentTextColor)
        }
        binding.btnPinHome.setOnClickListener {
            dialog.dismiss()
            goHome(context)
        }

        /*
         * Resalte del foco con control remoto.
         *
         * El estilo PinKey trae un fondo con estado `pressed` pero NO `focused`.
         * Con el dedo se ve al tocar; con el mando, moverse por el teclado no
         * cambiaba nada y había que escribir el PIN a ciegas, contando
         * posiciones. Es de las peores pantallas donde puede pasar: un dígito
         * mal y la única señal es que el PIN no funciona.
         *
         * Las teclas son óvalos (ver bg_pin_key), de ahí el resalte circular.
         */
        val remoto = RemoteControl.isEnabled(context)
        if (remoto) {
            (keys.map { it.first } + binding.pinKeyBackspace).forEach {
                RemoteControl.applyIconFocus(it, true)
            }
            RemoteControl.applyIconFocus(binding.tvPinCancel, true, circular = false, cornerRadiusDp = 16f)
        }

        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // Con el mando, empezar con el foco en el centro del teclado: desde el 5
        // se llega a cualquier dígito en dos pulsaciones.
        if (remoto) RemoteControl.focusWhenReady(binding.pinKey5)
    }
}
