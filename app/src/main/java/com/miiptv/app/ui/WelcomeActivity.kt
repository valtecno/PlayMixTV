package com.miiptv.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.miiptv.app.R
import com.miiptv.app.api.Session
import com.miiptv.app.databinding.ActivityWelcomeBinding
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.util.Servers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla de bienvenida.
 *
 * Aparece una sola vez, la primera vez que el usuario accede después de
 * ingresar un código nuevo. Le confirma que está dentro, le muestra hasta
 * cuándo tiene acceso y le da un acceso directo de contacto para renovar
 * o consultar dudas.
 *
 * Se omite si ya se mostró (flag en SharedPreferences) para no interrumpir
 * cada vez que se abre la app.
 */
class WelcomeActivity : AppCompatActivity() {

    companion object {
        private const val PREFS       = "miiptv_welcome"
        private const val KEY_SHOWN   = "welcome_shown_for"

        /**
         * Devuelve true si la pantalla de bienvenida debe mostrarse para la
         * cuenta activa. Se marca como vista en cuanto se llama, de modo que
         * incluso si algo falla al mostrarla, no vuelve a aparecer.
         */
        fun debesMostrar(context: Context): Boolean {
            if (!Session.isLoggedIn(context)) return false
            val clave = "${Session.server(context)}|${Session.username(context)}"
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_SHOWN, null) == clave) return false
            prefs.edit().putString(KEY_SHOWN, clave).apply()
            return true
        }
    }

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mostrarFechaVencimiento()

        binding.btnWelcomeStart.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        binding.tvWelcomeContact.setOnClickListener {
            val mensaje = "Hola, soy usuario de PlayMix TV y tengo una consulta."
            val url = "https://wa.me/56948714030?text=" + Uri.encode(mensaje)
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    private fun mostrarFechaVencimiento() {
        // La fecha de vencimiento viene de la respuesta de Xtream (campo exp_date).
        // Se guarda en Session al hacer login para poder mostrarla aquí.
        val expDate = Session.getExpDate(this)
        binding.tvWelcomeExpiry.text = when {
            expDate == null || expDate == "Unlimited" || expDate == "0" -> {
                getString(R.string.welcome_unlimited)
            }
            else -> {
                runCatching {
                    // Xtream devuelve el epoch en segundos como string
                    val epochSec = expDate.toLong()
                    val fecha = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                        .format(Date(epochSec * 1000))
                    getString(R.string.welcome_access_until, fecha)
                }.getOrElse {
                    getString(R.string.welcome_unlimited)
                }
            }
        }

        // Mostrar también el sistema al que pertenece
        val sistema = Servers.labelFor(Session.server(this))
        val titulo = "${getString(R.string.welcome_title)} — $sistema"
        binding.tvWelcomeTitle.text = titulo
    }

    override fun onBackPressed() {
        // Saltar hacia atrás lleva al login, no al Inicio. En la bienvenida
        // el botón correcto es "Explorar contenido".
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
