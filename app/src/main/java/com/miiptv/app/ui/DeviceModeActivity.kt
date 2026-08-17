package com.miiptv.app.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.miiptv.app.R
import com.miiptv.app.api.Session
import com.miiptv.app.databinding.ActivityDeviceModeBinding
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.util.RemoteControl

/**
 * Pantalla que aparece la primera vez: el usuario elige si va a usar la app
 * con el dedo (móvil) o con control remoto (TV). De esa elección depende cómo
 * se distribuye el inicio, la densidad de las grillas y si se resalta el foco
 * del control remoto en el resto de la app (ver [RemoteControl]).
 *
 * También se puede volver a abrir desde Cuenta para cambiar de modo.
 *
 * ---------------------------------------------------------------------------
 * ESTA PANTALLA SIEMPRE SE MANEJA CON REMOTO
 *
 * Acá el resalte de foco NO depende de [RemoteControl.isEnabled]: se aplica
 * siempre. Es la única pantalla donde no resaltarlo puede dejar al usuario
 * encerrado — si el aparato no tiene pantalla táctil y además no se ve dónde
 * está el foco, no hay manera de llegar hasta "TV" y confirmar. Los decos
 * baratos no siempre se declaran como televisores, así que la detección
 * automática no alcanza como única red de seguridad.
 * ---------------------------------------------------------------------------
 */
class DeviceModeActivity : AppCompatActivity() {

    companion object {
        /** Si viene en true, al terminar solo cierra en vez de seguir al inicio. */
        const val EXTRA_ONLY_CHANGE = "extra_only_change"
    }

    private lateinit var binding: ActivityDeviceModeBinding
    private var seleccion: String = DeviceMode.MOBILE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
        binding = ActivityDeviceModeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Arranca preseleccionado con lo más probable según el aparato
        seleccion = DeviceMode.get(this)

        binding.optMobile.setOnClickListener { select(DeviceMode.MOBILE) }
        binding.optTv.setOnClickListener { select(DeviceMode.TV) }
        binding.btnContinue.background = Appearance.withFocusState(
            this, Appearance.gradient(this, 12f), 12f
        )
        binding.btnContinue.setOnClickListener { confirm() }

        select(seleccion)

        // Que se vea de entrada dónde está el control remoto, sin tener que
        // pulsar una flecha a ciegas para averiguarlo.
        RemoteControl.focusWhenReady(
            if (seleccion == DeviceMode.TV) binding.optTv else binding.optMobile
        )
    }

    private fun select(mode: String) {
        seleccion = mode
        Appearance.applyChipState(binding.tvMobileTitle, mode == DeviceMode.MOBILE, 10f)
        Appearance.applyChipState(binding.tvTvTitle, mode == DeviceMode.TV, 10f)
        binding.optMobile.background = optionBackground(mode == DeviceMode.MOBILE)
        binding.optTv.background = optionBackground(mode == DeviceMode.TV)
    }

    /**
     * Fondo de una de las dos tarjetas de opción, con tres estados:
     *
     *  - **enfocada**: lavado difuminado del color de acento. Es lo que dice
     *    "el control remoto está acá".
     *  - **elegida**: vidrio con borde grueso de acento.
     *  - **en reposo**: vidrio con borde tenue.
     *
     * Antes solo existían los dos últimos, así que con el remoto se veía cuál
     * estaba elegida pero no sobre cuál te estabas moviendo: eran lo mismo hasta
     * que pulsabas OK.
     */
    private fun optionBackground(selected: Boolean): StateListDrawable {
        val reposo = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.displayMetrics.density * 14
            setColor(ContextCompat.getColor(this@DeviceModeActivity, R.color.glass_card))
            val ancho = (resources.displayMetrics.density * if (selected) 2 else 1).toInt()
            val color = if (selected) Appearance.accent(this@DeviceModeActivity)
            else ContextCompat.getColor(this@DeviceModeActivity, R.color.glass_border)
            setStroke(ancho, color)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Appearance.focusFill(this@DeviceModeActivity, 14f))
            addState(intArrayOf(), reposo)
        }
    }

    private fun confirm() {
        // Acá queda guardada la elección. RemoteControl la lee de esta misma
        // preferencia, así que elegir "TV" deja el manejo con control remoto
        // activado de forma permanente, hasta que se cambie desde Cuenta.
        DeviceMode.set(this, seleccion)

        if (intent.getBooleanExtra(EXTRA_ONLY_CHANGE, false)) {
            // Se cambió desde Ajustes: se reinicia el inicio para aplicar la distribución
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } else {
            val siguiente =
                if (Session.isLoggedIn(this)) MainActivity::class.java else LoginActivity::class.java
            startActivity(Intent(this, siguiente))
        }
        finish()
    }
}
