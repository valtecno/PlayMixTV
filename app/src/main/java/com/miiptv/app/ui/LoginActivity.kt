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
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Servidores propios precargados. El usuario final solo elige uno tocándolo
 * y completa usuario/contraseña — no necesita escribir ninguna URL.
 * Para agregar o cambiar servidores, editá esta lista.
 */
private data class ServerOption(val label: String, val url: String)

private val SERVERS = listOf(
    ServerOption("Sistema L", "http://xdplayer.tv:8080"),
    ServerOption("Sistema XL", "http://moontools.site:8080")
)

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var selectedServer = SERVERS[0]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.chipServer1.text = SERVERS[0].label
        binding.chipServer2.text = SERVERS[1].label

        binding.chipServer1.setOnClickListener { selectServer(SERVERS[0]) }
        binding.chipServer2.setOnClickListener { selectServer(SERVERS[1]) }
        selectServer(SERVERS[0]) // primero seleccionado por defecto

        binding.etUsername.setText(Session.username(this))

        binding.btnLogin.setOnClickListener { attemptLogin() }
    }

    private fun selectServer(server: ServerOption) {
        selectedServer = server
        highlightChip(binding.chipServer1, server == SERVERS[0])
        highlightChip(binding.chipServer2, server == SERVERS[1])
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
