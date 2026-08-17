package com.miiptv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.miiptv.app.R
import com.miiptv.app.api.LoginResponse
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

        binding.etUsername.setText(Session.username(this))

        binding.btnLogin.background = Appearance.withFocusState(
            this, Appearance.gradient(this, 12f), 12f
        )
        binding.btnLogin.setOnClickListener { attemptLogin() }

        // Con remoto, empezar con el foco puesto en el sistema preseleccionado
        if (RemoteControl.isEnabled(this)) {
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

        val user = binding.etUsername.text.toString().trim()
        val pass = binding.etPassword.text.toString().trim()

        if (user.isBlank() || pass.isBlank()) {
            Toast.makeText(this, "Completa usuario y contraseña", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)
        Session.save(this, servidor.url, user, pass)

        Session.api(this).login(user, pass).enqueue(object : Callback<LoginResponse> {
            override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                setLoading(false)
                val auth = response.body()?.userInfo?.auth
                if (response.isSuccessful && auth == 1) {
                    // Queda guardada para poder saltar entre Sistema L y XL sin re-escribirla
                    Accounts.save(this@LoginActivity, servidor.url, user, pass)
                    Catalog.clear()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    Session.logout(this@LoginActivity)
                    Toast.makeText(this@LoginActivity, getString(R.string.login_error), Toast.LENGTH_LONG).show()
                }
            }

            override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                setLoading(false)
                Session.logout(this@LoginActivity)
                Toast.makeText(this@LoginActivity, getString(R.string.login_error) + ": ${t.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading && selectedServer != null
    }
}
