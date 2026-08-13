package com.miiptv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.miiptv.app.api.LoginResponse
import com.miiptv.app.api.Session
import com.miiptv.app.databinding.ActivityLoginBinding
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Prellenar si ya había datos guardados (por si el login falló antes)
        binding.etServer.setText(Session.server(this))
        binding.etUsername.setText(Session.username(this))

        binding.btnLogin.setOnClickListener { attemptLogin() }
    }

    private fun attemptLogin() {
        val server = binding.etServer.text.toString().trim()
        val user = binding.etUsername.text.toString().trim()
        val pass = binding.etPassword.text.toString().trim()

        if (server.isBlank() || user.isBlank() || pass.isBlank()) {
            Toast.makeText(this, "Completa todos los campos", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)
        // Guardamos primero para poder construir el cliente Retrofit con la baseUrl correcta
        Session.save(this, server, user, pass)

        Session.api(this).login(user, pass).enqueue(object : Callback<LoginResponse> {
            override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                setLoading(false)
                val auth = response.body()?.userInfo?.auth
                if (response.isSuccessful && auth == 1) {
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    Session.logout(this@LoginActivity)
                    Toast.makeText(this@LoginActivity, getString(com.miiptv.app.R.string.login_error), Toast.LENGTH_LONG).show()
                }
            }

            override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                setLoading(false)
                Session.logout(this@LoginActivity)
                Toast.makeText(this@LoginActivity, getString(com.miiptv.app.R.string.login_error) + ": ${t.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
    }
}
