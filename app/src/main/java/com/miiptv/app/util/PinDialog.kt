package com.miiptv.app.util

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.miiptv.app.R
import com.miiptv.app.databinding.DialogPinBinding

/**
 * Teclado numérico de PIN, a pantalla completa, con el mismo estilo visual
 * del resto de la app (tarjetas "vidrio", degradado de marca, dorado como
 * color activo). Reemplaza el diálogo viejo con EditText + teclado del sistema.
 */
object PinDialog {

    private const val PIN_LENGTH = 4

    /** Pide el PIN existente. Llama a onSuccess si es correcto. */
    fun ask(context: Context, onSuccess: () -> Unit) {
        showKeypad(
            context,
            title = context.getString(R.string.pin_ask_title),
            subtitle = context.getString(R.string.pin_ask_subtitle)
        ) { pin, dialog, reset ->
            if (Parental.checkPin(context, pin)) {
                dialog.dismiss()
                onSuccess()
            } else {
                Toast.makeText(context, context.getString(R.string.pin_wrong), Toast.LENGTH_SHORT).show()
                reset()
            }
        }
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
     * Arma y muestra el teclado. [onComplete] se llama al juntar los 4 dígitos;
     * recibe el PIN escrito, el propio diálogo (para cerrarlo si está bien) y
     * una función [reset] para limpiar los puntos si hay que reintentar.
     */
    private fun showKeypad(
        context: Context,
        title: String,
        subtitle: String,
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

        binding.tvPinCancel.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
