package com.miiptv.app.util

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.Toast

object PinDialog {

    /** Pide el PIN existente. Llama a onSuccess si es correcto. */
    fun ask(context: Context, onSuccess: () -> Unit) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        AlertDialog.Builder(context)
            .setTitle("Control parental")
            .setMessage("Ingresa el PIN para continuar")
            .setView(input)
            .setPositiveButton("Aceptar") { _, _ ->
                if (Parental.checkPin(context, input.text.toString().trim())) {
                    onSuccess()
                } else {
                    Toast.makeText(context, "PIN incorrecto", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Crea un PIN nuevo (usado la primera vez, en Ajustes). */
    fun create(context: Context, onSuccess: () -> Unit) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Nuevo PIN (4 dígitos)"
        }
        AlertDialog.Builder(context)
            .setTitle("Crear PIN de control parental")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length < 4) {
                    Toast.makeText(context, "El PIN debe tener al menos 4 dígitos", Toast.LENGTH_SHORT).show()
                } else {
                    Parental.setPin(context, pin)
                    Toast.makeText(context, "PIN creado", Toast.LENGTH_SHORT).show()
                    onSuccess()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
