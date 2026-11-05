package com.miiptv.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.miiptv.app.R
import com.miiptv.app.api.CodeValidationResponse
import com.miiptv.app.api.LoginResponse
import com.miiptv.app.api.PanelApi
import com.miiptv.app.api.Session
import com.miiptv.app.databinding.ActivityLoginBinding
import com.miiptv.app.util.Accounts
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.Catalog
import com.miiptv.app.util.CrashLogger
import com.miiptv.app.util.ErrorDiagnosis
import com.miiptv.app.util.DataSync
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.util.RemoteControl
import com.miiptv.app.util.Servers
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class LoginActivity : AppCompatActivity() {

    companion object {
        /** URL del servidor a preseleccionar al abrir (viene de "Cambiar de cuenta"). */
        const val EXTRA_SERVER_URL = "extra_server_url"
    }

    private lateinit var binding: ActivityLoginBinding

    private var selectedServer: Servers.Server? = null

    /** Mientras se valida el código. Se usa en vez de deshabilitar el botón: un botón deshabilitado pierde el foco del control remoto. */
    private var cargando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ver el mismo bloque en MainActivity.onCreate: si la app se cerró
        // sola la vez anterior y esta pantalla es la que abre ahora (sin
        // sesión activa), el diagnóstico también tiene que poder verse acá.
        mostrarCierreAnteriorSiHay()
        DeviceMode.lockPortraitIfMobile(this)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        verificarServidores()

        // Servidor de respaldo: solo se usa si el panel no informa el suyo. Si
        // venimos de "Cambiar de cuenta", el pedido; si no, el primero.
        val preselect = intent.getStringExtra(EXTRA_SERVER_URL)
        selectedServer = preselect?.let { Servers.byUrl(it) } ?: Servers.default

        // Sin preselección: al "Agregar otra cuenta" desde Ajustes esto
        // precargaba el usuario de la cuenta YA activa en los campos, como si
        // se estuviera editando esa misma cuenta en vez de cargar una nueva.
        // Los campos arrancan vacíos siempre; la única precarga real es la
        // del servidor (arriba, como respaldo), que si tiene sentido reusar.

        binding.btnLogin.background = Appearance.withFocusState(
            this, Appearance.gradient(this, 12f), 12f
        )
        binding.btnLogin.setOnClickListener { attemptLogin() }

        // El botón de ojo no se usa en modo código pero el binding lo necesita
        // existir — está oculto en el XML.

        if (RemoteControl.isEnabled(this)) {
            RemoteControl.focusWhenReady(binding.etPassword)
        }
    }

    override fun onResume() {
        super.onResume()
        // Si esta pantalla ya estaba creada y se vuelve a ella (se abrió
        // encima algún diálogo del sistema, o el diálogo de "Cuentas" se
        // cerró justo al mismo tiempo que arrancaba esta Activity), el chip
        // del servidor se quedaba sin ningún indicador visual de selección:
        // el foco del control remoto no caía en ningún lado. onCreate solo
        // corre una vez, así que esta pantalla necesita su propio resguardo
        // al volver a primer plano.
        if (RemoteControl.isEnabled(this) && currentFocus == null) {
            RemoteControl.focusWhenReady(binding.etPassword)
        }
    }

    /**
     * El sistema (L o XL) ya no se elige a mano: lo asigna el código al
     * validarlo (ver attemptLogin). Acá solo se revisa que la compilación
     * tenga servidores configurados.
     */
    private fun verificarServidores() {
        if (Servers.all.isEmpty()) {
            // Solo pasa con una compilación mal configurada. Es preferible
            // avisarlo a que la pantalla quede muda y el botón no haga nada.
            binding.btnLogin.isEnabled = false
            Toast.makeText(this, R.string.login_sin_servidores, Toast.LENGTH_LONG).show()
        }
    }

    private fun attemptLogin() {
        if (cargando) return
        val servidor = selectedServer
        if (servidor == null) {
            Toast.makeText(this, R.string.login_sin_servidores, Toast.LENGTH_LONG).show()
            return
        }

        val codigo = binding.etPassword.text.toString().trim().uppercase()

        if (codigo.isBlank()) {
            Toast.makeText(this, "Ingresa tu código de acceso", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)

        // Paso 1: validar el código contra el panel privado de PlayMix.
        // Si es válido, el panel devuelve el usuario y contraseña reales
        // del panel Xtream de origen. La app nunca los muestra al usuario.
        PanelApi.instance.validateCode(codigo).enqueue(object : retrofit2.Callback<CodeValidationResponse> {
            override fun onResponse(
                call: retrofit2.Call<CodeValidationResponse>,
                response: retrofit2.Response<CodeValidationResponse>
            ) {
                val data = response.body()
                if (!response.isSuccessful || data == null || !data.isOk) {
                    setLoading(false)
                    // Mostrar el mensaje exacto del panel, o el HTTP status si no hay body
                    val msg = when {
                        data?.mensaje != null -> data.mensaje
                        // Hubo respuesta del servidor (HTTP no exitoso): no es la
                        // conexión del usuario, aunque el código esté vacío.
                        !response.isSuccessful ->
                            ErrorDiagnosis.mensaje(this@LoginActivity, ErrorDiagnosis.causaDeHttp(response.code()))
                        else -> getString(R.string.login_error_invalid)
                    }
                    Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
                    return
                }

                val user = data.username!!
                val pass = data.password!!
                // El código determina el sistema: el panel devuelve su servidor
                // (dns_servidor), que es la URL con la que se guarda la cuenta
                // (favoritos y cambio de sistema dependen de esa URL, no la toques).
                // Si no la informa, se usa el sistema que dice el panel ("Sistema L"
                // o "Sistema XL") y, en último caso, el de respaldo.
                val urlFinal = data.server?.takeIf { it.isNotBlank() }
                    ?: Servers.byLabel(data.sistema)?.url
                    ?: servidor.url

                // Paso 2: con las credenciales reales, conectar al panel Xtream.
                // invalidateApi() primero: si había una sesión anterior cacheada
                // (otro usuario, otro servidor), Session.api() devolvería el
                // Retrofit viejo en vez del nuevo, y el login iría al servidor
                // equivocado sin saberlo.
                Session.save(this@LoginActivity, urlFinal, user, pass)
                Session.invalidateApi()
                Session.api(this@LoginActivity).login(user, pass)
                    .enqueue(object : retrofit2.Callback<LoginResponse> {
                        override fun onResponse(
                            call: retrofit2.Call<LoginResponse>,
                            response: retrofit2.Response<LoginResponse>
                        ) {
                            if (response.isSuccessful && response.body()?.userInfo?.auth == 1) {
                                Accounts.save(this@LoginActivity, urlFinal, user, pass)
                                Session.saveExpDate(this@LoginActivity, response.body()?.userInfo?.expDate)
                                Catalog.clear()
                                // Antes de abrir el Inicio: si esta cuenta ya tenía favoritos/
                                // continuar viendo/historial guardados en la nube (otro equipo,
                                // o una reinstalación), se restauran acá. Con sesión nueva no hay
                                // nada que bajar y esto no demora casi nada; con mala conexión se
                                // sigue de largo igual (mejor esfuerzo, nunca bloquea el login). El
                                // círculo de carga se deja encendido hasta que esto termina: apagarlo
                                // antes dejaba un momento sin ningún indicador antes de pasar a Inicio.
                                DataSync.restore(this@LoginActivity) {
                                    setLoading(false)
                                    // Después del login siempre se pasa por el selector de perfiles.
                                    // ProfileSelectorActivity se encarga de pasar a WelcomeActivity
                                    // la primera vez, o directo a MainActivity en las siguientes.
                                    startActivity(Intent(this@LoginActivity, ProfileSelectorActivity::class.java))
                                    finish()
                                }
                            } else {
                                setLoading(false)
                                Session.logout(this@LoginActivity)
                                // Hubo respuesta del panel (auth != 1): la cuenta no es
                                // válida, no es un problema de conexión.
                                Toast.makeText(
                                    this@LoginActivity,
                                    getString(R.string.login_error_invalid),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        override fun onFailure(call: retrofit2.Call<LoginResponse>, t: Throwable) {
                            setLoading(false)
                            Session.logout(this@LoginActivity)
                            val causa = ErrorDiagnosis.causaDeFallo(this@LoginActivity, t)
                            Toast.makeText(
                                this@LoginActivity,
                                ErrorDiagnosis.mensaje(this@LoginActivity, causa),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    })
            }

            override fun onFailure(call: retrofit2.Call<CodeValidationResponse>, t: Throwable) {
                setLoading(false)
                val causa = ErrorDiagnosis.causaDeFallo(this@LoginActivity, t)
                Toast.makeText(
                    this@LoginActivity,
                    ErrorDiagnosis.mensaje(this@LoginActivity, causa),
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    private fun setLoading(loading: Boolean) {
        cargando = loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        // No se deshabilita el botón al cargar: una vista deshabilitada pierde el
        // foco y el control remoto queda sin dónde estar al terminar.
        binding.btnLogin.isEnabled = selectedServer != null
    }

    /** Ver el comentario en MainActivity.mostrarCierreAnteriorSiHay: mismo mecanismo. */
    private fun mostrarCierreAnteriorSiHay() {
        val texto = CrashLogger.pendingCrash(this) ?: return
        CrashLogger.marcarMostrado(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_log_title)
            .setMessage(texto)
            .setPositiveButton(R.string.diag_copy) { _, _ ->
                val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("PlayMix crash", texto))
                Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.ok, null)
            .setCancelable(false)
            .show()
    }
}
