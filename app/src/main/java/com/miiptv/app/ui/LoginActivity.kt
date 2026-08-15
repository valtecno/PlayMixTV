package com.miiptv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.miiptv.app.R
import com.miiptv.app.api.LoginResponse
import com.miiptv.app.api.Session
import com.miiptv.app.databinding.ActivityLoginBinding
import com.miiptv.app.util.Accounts
import com.miiptv.app.util.Catalog
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
    private var selectedServer = Servers.default

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.chipServer1.text = Servers.all[0].label
        binding.chipServer2.text = Servers.all[1].label

        binding.chipServer1.setOnClickListener { selectServer(Servers.all[0]) }
        binding.chipServer2.setOnClickListener { selectServer(Servers.all[1]) }

        // Si venimos de "Cambiar de cuenta", arrancamos en el servidor pedido
        val preselect = intent.getStringExtra(EXTRA_SERVER_URL)
        selectServer(preselect?.let { Servers.byUrl(it) } ?: Servers.default)

        binding.etUsername.setText(Session.username(this))

        binding.btnLogin.setOnClickListener { attemptLogin() }
    }

    private fun selectServer(server: Servers.Server) {
        selectedServer = server
        highlightChip(binding.chipServer1, server == Servers.all[0])
        highlightChip(binding.chipServer2, server == Servers.all[1])
    }

    private fun highlightChip(chip: TextView, selected: Boolean) {
        chip.setBackgroundResource(if (selected) R.drawable.bg_category_chip else R.drawable.bg_glass_card)
    }

    private fun attemptLogin() {
        val user = binding.etUsername.text.toString().trim()
        val pass = binding.etPassword.text.toString().trim()

        if (user.isBlank() || pass.isBlank()) {
            Toast.makeText(this, "Completa usuario y contraseña", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)
        Session.save(this, selectedServer.url, user, pass)

        Session.api(this).login(user, pass).enqueue(object : Callback<LoginResponse> {
            override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                setLoading(false)
                val auth = response.body()?.userInfo?.auth
                if (response.isSuccessful && auth == 1) {
                    // Queda guardada para poder saltar entre Sistema L y XL sin re-escribirla
                    Accounts.save(this@LoginActivity, selectedServer.url, user, pass)
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
        binding.btnLogin.isEnabled = !loading
    }
}
