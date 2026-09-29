package com.miiptv.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.miiptv.app.R
import com.miiptv.app.api.Session
import com.miiptv.app.databinding.ActivityWelcomeBinding
import com.miiptv.app.util.DeviceMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla de bienvenida.
 *
 * Aparece una sola vez, la primera vez que el usuario accede después de
 * ingresar un código nuevo. Le confirma que está dentro y le muestra hasta
 * cuándo tiene acceso. No dice a qué sistema/servidor pertenece esa cuenta
 * (es un detalle interno, no algo que el usuario necesite ver) ni tiene
 * accesos de contacto: eso ya vive en el menú de Cuenta.
 *
 * Se cierra sola: al tocar "Explorar contenido", o a los 5 segundos si nadie
 * la toca (pensado para control remoto/TV, donde nadie llega a apretar nada
 * antes de que la app arranque de verdad).
 *
 * Se omite si ya se mostró (flag en SharedPreferences) para no interrumpir
 * cada vez que se abre la app.
 */
class WelcomeActivity : AppCompatActivity() {

    companion object {
        private const val PREFS       = "miiptv_welcome"
        private const val KEY_SHOWN   = "welcome_shown_for"
        private const val AUTO_CLOSE_MS = 5000L

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
    private val autoCloseHandler = Handler(Looper.getMainLooper())
    private val autoCloseRunnable = Runnable { entrar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mostrarFechaVencimiento()

        binding.btnWelcomeStart.setOnClickListener { entrar() }
        autoCloseHandler.postDelayed(autoCloseRunnable, AUTO_CLOSE_MS)
    }

    override fun onDestroy() {
        // Si ya se salió por el botón (o por Atrás), que el cierre automático
        // no dispare igual un segundo "entrar()" un rato después.
        autoCloseHandler.removeCallbacks(autoCloseRunnable)
        super.onDestroy()
    }

    private fun entrar() {
        if (isFinishing) return
        startActivity(Intent(this, MainActivity::class.java))
        finish()
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
    }

    override fun onBackPressed() {
        // Saltar hacia atrás lleva al login, no al Inicio. En la bienvenida
        // el botón correcto es "Explorar contenido".
        entrar()
    }
}
