package com.miiptv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.miiptv.app.R
import com.miiptv.app.api.CodeValidationResponse
import com.miiptv.app.api.LoginResponse
import com.miiptv.app.api.PanelApi
import com.miiptv.app.api.Session
import com.miiptv.app.databinding.ActivityLoginBinding
import com.miiptv.app.databinding.ItemServerChipBinding
import com.miiptv.app.util.Accounts
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.Catalog
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

    /** Chips creados, en el mismo orden que [Servers.all], para poder repintarlos. */
    private val chips = mutableListOf<TextView>()
    private var selectedServer: Servers.Server? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildServerChips()

        // Si venimos de "Cambiar de cuenta", arrancamos en el servidor pedido
        val preselect = intent.getStringExtra(EXTRA_SERVER_URL)
        selectServer(preselect?.let { Servers.byUrl(it) } ?: Servers.default)

        // Sin preselección: al "Agregar otra cuenta" desde Ajustes esto
        // precargaba el usuario de la cuenta YA activa en los campos, como si
        // se estuviera editando esa misma cuenta en vez de cargar una nueva.
        // Los campos arrancan vacíos siempre; la única precarga real es la
        // del servidor (selectServer, arriba), que si tiene sentido reusar.

        binding.btnLogin.background = Appearance.withFocusState(
            this, Appearance.gradient(this, 12f), 12f
        )
        binding.btnLogin.setOnClickListener { attemptLogin() }

        // El botón de ojo no se usa en modo código pero el binding lo necesita
        // existir — está oculto en el XML.

        if (RemoteControl.isEnabled(this)) {
            RemoteControl.focusWhenReady(chips.getOrNull(indiceSeleccionado()))
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
            RemoteControl.focusWhenReady(chips.getOrNull(indiceSeleccionado()))
        }
    }

    private fun indiceSeleccionado(): Int =
        Servers.all.indexOfFirst { it.id == selectedServer?.id }.coerceAtLeast(0)

    /**
     * Dibuja un chip por cada servidor configurado, repartiendo el ancho en
     * partes iguales.
     *
     * Antes los dos chips estaban escritos en el XML y referenciados por id, o
     * sea que la app soportaba exactamente dos servidores: agregar un tercero
     * pedía tocar el layout y esta clase. Ahora agregar servidores es editar
     * una línea de gradle.properties.
     */
    private fun buildServerChips() {
        binding.serverChips.removeAllViews()
        chips.clear()

        val servidores = Servers.all
        if (servidores.isEmpty()) {
            // Solo pasa con una compilación mal configurada. Es preferible
            // avisarlo a que la pantalla quede muda y el botón no haga nada.
            binding.btnLogin.isEnabled = false
            Toast.makeText(this, R.string.login_sin_servidores, Toast.LENGTH_LONG).show()
            return
        }

        val margen = resources.getDimensionPixelSize(R.dimen.server_chip_gap)
        servidores.forEachIndexed { i, servidor ->
            val chip = ItemServerChipBinding.inflate(layoutInflater, binding.serverChips, false).root
            chip.text = servidor.label
            chip.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                if (i > 0) marginStart = margen
                if (i < servidores.lastIndex) marginEnd = margen
            }
            chip.setOnClickListener { selectServer(servidor) }
            binding.serverChips.addView(chip)
            chips += chip
        }
    }

    private fun selectServer(server: Servers.Server?) {
        selectedServer = server ?: Servers.default
        val elegido = selectedServer
        Servers.all.forEachIndexed { i, servidor ->
            chips.getOrNull(i)?.let { highlightChip(it, servidor.id == elegido?.id) }
        }
    }

    /**
     * Antes se asignaba un drawable plano, sin estados: con el control remoto no
     * había forma de ver sobre qué sistema estabas parado antes de pulsar OK.
     * Appearance.applyChipState devuelve un fondo con estado enfocado incluido.
     */
    private fun highlightChip(chip: TextView, selected: Boolean) {
        Appearance.applyChipState(chip, selected, cornerRadiusDp = 12f)
    }

    private fun attemptLogin() {
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
                    val msg = data?.mensaje ?: getString(R.string.login_error)
                    Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
                    return
                }

                val user = data.username!!
                val pass = data.password!!
                // Si el panel devuelve un servidor específico para este código,
                // lo usamos; si no, usamos el que eligió el usuario en los chips.
                val urlFinal = data.server?.takeIf { it.isNotBlank() } ?: servidor.url

                // Paso 2: con las credenciales reales, conectar al panel Xtream.
                Session.save(this@LoginActivity, urlFinal, user, pass)
                Session.api(this@LoginActivity).login(user, pass)
                    .enqueue(object : retrofit2.Callback<LoginResponse> {
                        override fun onResponse(
                            call: retrofit2.Call<LoginResponse>,
                            response: retrofit2.Response<LoginResponse>
                        ) {
                            setLoading(false)
                            if (response.isSuccessful && response.body()?.userInfo?.auth == 1) {
                                Accounts.save(this@LoginActivity, urlFinal, user, pass)
                                Catalog.clear()
                                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                                finish()
                            } else {
                                Session.logout(this@LoginActivity)
                                Toast.makeText(
                                    this@LoginActivity,
                                    getString(R.string.login_error),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        override fun onFailure(call: retrofit2.Call<LoginResponse>, t: Throwable) {
                            setLoading(false)
                            Session.logout(this@LoginActivity)
                            Toast.makeText(
                                this@LoginActivity,
                                getString(R.string.login_error),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    })
            }

            override fun onFailure(call: retrofit2.Call<CodeValidationResponse>, t: Throwable) {
                setLoading(false)
                Toast.makeText(
                    this@LoginActivity,
                    getString(R.string.login_error),
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading && selectedServer != null
    }
}
