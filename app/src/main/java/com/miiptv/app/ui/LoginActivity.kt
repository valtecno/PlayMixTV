package com.miiptv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.miiptv.app.R
import com.miiptv.app.api.LoginResponse
import com.miiptv.app.api.Session
import com.miiptv.app.databinding.ActivityLoginBinding
import com.miiptv.app.util.Accounts
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.Catalog
import com.miiptv.app.util.DeviceMode
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val httpClient = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Estilo del botón (Mantiene tu diseño original)
        binding.btnLogin.background = Appearance.withFocusState(
            this, Appearance.gradient(this, 12f), 12f
        )
        binding.btnLogin.setOnClickListener { attemptLogin() }
    }

    private fun attemptLogin() {
        // Lee el PIN y asegura que esté en mayúsculas
        val pin = binding.etPinCode.text.toString().trim().uppercase()

        if (pin.length < 6) {
            Toast.makeText(this, "Ingresa un PIN válido de 6 caracteres", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)

        // 1. Consultar tu nuevo panel de accesos
        val urlApi = "https://valtecno.cl/disc/paneltv/api.php?codigo=$pin"
        val request = Request.Builder().url(urlApi).build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    setLoading(false)
                    Toast.makeText(this@LoginActivity, "Error conectando al panel de acceso", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                runOnUiThread {
                    try {
                        val json = JSONObject(responseBody ?: "")
                        if (json.optString("status") == "success") {
                            // Extrae los datos reales devueltos por el panel
                            val userReal = json.getString("usuario_real")
                            val passReal = json.getString("password_real")
                            val dnsReal = json.getString("dns_servidor")
                            
                            // 2. Conectarse al servidor de streaming con los datos reales
                            loginToStreamingServer(dnsReal, userReal, passReal)
                        } else {
                            setLoading(false)
                            val msg = json.optString("mensaje", "Código inválido o ya utilizado")
                            Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        setLoading(false)
                        Toast.makeText(this@LoginActivity, "Error leyendo los datos del panel", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun loginToStreamingServer(serverUrl: String, user: String, pass: String) {
        // Ejecuta tu lógica original de Retrofit hacia el servidor IPTV
        Session.save(this, serverUrl, user, pass)

        Session.api(this).login(user, pass).enqueue(object : retrofit2.Callback<LoginResponse> {
            override fun onResponse(call: retrofit2.Call<LoginResponse>, response: retrofit2.Response<LoginResponse>) {
                setLoading(false)
                val auth = response.body()?.userInfo?.auth
                if (response.isSuccessful && auth == 1) {
                    // Guarda la cuenta y avanza a la pantalla principal
                    Accounts.save(this@LoginActivity, serverUrl, user, pass)
                    Catalog.clear()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    Session.logout(this@LoginActivity)
                    Toast.makeText(this@LoginActivity, getString(R.string.login_error), Toast.LENGTH_LONG).show()
                }
            }

            override fun onFailure(call: retrofit2.Call<LoginResponse>, t: Throwable) {
                setLoading(false)
                Session.logout(this@LoginActivity)
                Toast.makeText(this@LoginActivity, getString(R.string.login_error) + ": ${t.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.etPinCode.isEnabled = !loading
    }
}