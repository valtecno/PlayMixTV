package com.miiptv.app.util

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifra la contraseña del panel antes de guardarla en SharedPreferences.
 *
 * Antes se guardaba tal cual (texto plano). Quedaba fuera de la copia de
 * seguridad (ver backup_rules.xml), pero seguía legible por cualquiera con
 * acceso al almacenamiento del teléfono -- rooteado, con el depurador de
 * Android, o extrayendo el `run-as` en un dispositivo de desarrollo.
 *
 * La clave de cifrado vive en el Android Keystore: nunca sale del chip de
 * seguridad del aparato, así que ni siquiera copiando el archivo de
 * preferencias a otro equipo se puede recuperar la contraseña sin ese
 * mismo hardware.
 *
 * En Android 5.0-5.1 (API 21-22) el Keystore no soporta claves AES, así que
 * ahí se guarda sin cifrar como antes -- son muy pocos aparatos IPTV con esa
 * versión y es preferible que la app siga funcionando a que deje de guardar
 * la sesión. `encrypt`/`decrypt` devuelven null en ese caso y quien llama
 * cae al comportamiento anterior.
 */
object CryptoUtil {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "playmix_creds_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private val soportado: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    private fun claveSecreta(): SecretKey? {
        if (!soportado) return null
        return try {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
                val generador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                generador.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generador.generateKey()
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Devuelve texto cifrado en Base64 (IV + datos), o null si no se pudo cifrar. */
    fun encrypt(texto: String): String? {
        val clave = claveSecreta() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, clave) }
            val cifrado = cipher.doFinal(texto.toByteArray(Charsets.UTF_8))
            // El IV lo genera el propio Cipher y hace falta para descifrar;
            // no es secreto, así que va pegado adelante del resultado.
            val salida = cipher.iv + cifrado
            Base64.encodeToString(salida, Base64.NO_WRAP)
        } catch (t: Throwable) {
            null
        }
    }

    /** Inversa de [encrypt]. Devuelve null si el valor no está cifrado o no se pudo descifrar. */
    fun decrypt(base64: String): String? {
        val clave = claveSecreta() ?: return null
        return try {
            val datos = Base64.decode(base64, Base64.NO_WRAP)
            // GCM en Android usa un IV de 12 bytes.
            val iv = datos.copyOfRange(0, 12)
            val cifrado = datos.copyOfRange(12, datos.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, clave, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(cifrado), Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }
}
