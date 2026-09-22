package com.miiptv.app.api

import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Endpoint del panel privado de PlayMix que valida el código de acceso.
 *
 * Flujo completo:
 *   App  →  código (ej: "8B4F9")  →  este endpoint
 *        ←  {"status":"ok","username":"u","password":"p","server":"http://..."}
 *   App  →  usa username+password para conectar al panel Xtream de origen
 *
 * Si el código no existe o ya fue usado devuelve:
 *   {"status":"error","mensaje":"Código inválido"}
 */
interface PanelApi {

    @GET("api.php")
    fun validateCode(
        @Query("code") code: String
    ): Call<CodeValidationResponse>

    companion object {
        private const val BASE_URL = "https://valtecno.cl/disc/paneltv/"

        fun create(): PanelApi = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PanelApi::class.java)

        // Instancia única reutilizable
        val instance: PanelApi by lazy { create() }
    }
}
