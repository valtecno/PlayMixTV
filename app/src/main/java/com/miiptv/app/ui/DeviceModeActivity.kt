package com.miiptv.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.miiptv.app.api.Session
import com.miiptv.app.databinding.ActivityDeviceModeBinding
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.DeviceMode

/**
 * Pantalla que aparece la primera vez: el usuario elige si va a usar la app
 * con el dedo (móvil) o con control remoto (TV). De esa elección depende cómo
 * se distribuye el inicio y la densidad de las grillas.
 *
 * También se puede volver a abrir desde Cuenta para cambiar de modo.
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
        binding.btnContinue.background = Appearance.gradient(this, 12f)
        binding.btnContinue.setOnClickListener { confirm() }

        select(seleccion)
    }

    private fun select(mode: String) {
        seleccion = mode
        Appearance.applyChipState(binding.tvMobileTitle, mode == DeviceMode.MOBILE, 10f)
        Appearance.applyChipState(binding.tvTvTitle, mode == DeviceMode.TV, 10f)
        binding.optMobile.background = optionBackground(mode == DeviceMode.MOBILE)
        binding.optTv.background = optionBackground(mode == DeviceMode.TV)
    }

    private fun optionBackground(selected: Boolean) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = resources.displayMetrics.density * 14
            setColor(androidx.core.content.ContextCompat.getColor(this@DeviceModeActivity, com.miiptv.app.R.color.glass_card))
            val ancho = (resources.displayMetrics.density * if (selected) 2 else 1).toInt()
            val color = if (selected) Appearance.accent(this@DeviceModeActivity)
            else androidx.core.content.ContextCompat.getColor(this@DeviceModeActivity, com.miiptv.app.R.color.glass_border)
            setStroke(ancho, color)
        }

    private fun confirm() {
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
