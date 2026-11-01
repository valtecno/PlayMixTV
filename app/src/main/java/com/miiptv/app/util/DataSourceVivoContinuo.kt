package com.miiptv.app.util

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Fuente para canales en vivo por .ts que NO termina cuando el panel corta.
 *
 * Los paneles Xtream cierran la conexión de un canal .ts cada tanto, o la red
 * se cae un instante. Para ExoPlayer eso era el fin del video (STATE_ENDED) o
 * un error que recién se reintentaba al vaciarse el búfer: la imagen se
 * congelaba, quedaba en negro unos segundos y el canal se rearmaba desde
 * cero; y tras tres cortes ya no se reconectaba más.
 *
 * Esta fuente vuelve a conectar sola, en segundo plano, mientras el
 * reproductor sigue mostrando lo que ya tenía guardado, y empalma la
 * continuación con [TsEmpalme] para que ExoPlayer vea una sola transmisión
 * continua. Recién si el canal no responde tras varios intentos avisa el
 * error (y ahí actúa la reconexión de siempre de la pantalla).
 */
class DataSourceVivoContinuo(private val fabrica: DataSource.Factory) : DataSource {

    private val oyentes = mutableListOf<TransferListener>()
    private var empalme = TsEmpalme()
    private val lectura = ByteArray(64 * 1024)

    private var interno: DataSource? = null
    private var spec: DataSpec? = null
    private var salida = ByteArray(0)
    private var posSalida = 0
    private var fallos = 0
    private var recibidoConexion = 0L
    private var recibidoTotal = 0L

    @Volatile private var cerrado = true

    override fun addTransferListener(transferListener: TransferListener) {
        oyentes.add(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        // Un canal en vivo no se retoma "desde el byte N": siempre desde el presente.
        val s = dataSpec.buildUpon()
            .setPosition(0)
            .setLength(C.LENGTH_UNSET.toLong())
            .build()
        spec = s
        cerrado = false
        salida = ByteArray(0)
        posSalida = 0
        // Si ExoPlayer vuelve a abrir esta fuente, arma todo de nuevo: el
        // empalme arranca de cero igual que el reproductor.
        empalme = TsEmpalme()
        fallos = 0
        recibidoTotal = 0
        // Si la primera conexión falla, el error sube tal cual: la pantalla
        // decide (otro formato, aviso de cuenta vencida, etc.).
        conectar(s)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (posSalida < salida.size) {
                val n = minOf(length, salida.size - posSalida)
                System.arraycopy(salida, posSalida, buffer, offset, n)
                posSalida += n
                return n
            }
            if (cerrado) throw InterruptedIOException("fuente cerrada")
            val ds = interno ?: throw IOException("sin conexión")
            val leido = try {
                ds.read(lectura, 0, lectura.size)
            } catch (e: IOException) {
                // Cancelación de ExoPlayer (no un corte): se respeta. Ojo: el
                // tiempo de espera agotado (SocketTimeoutException) también es
                // InterruptedIOException, pero ese sí es un corte.
                if (esCancelacion(e)) throw e
                // Nunca llegó nada: no es un corte, es que el canal no anda.
                if (cerrado || recibidoTotal == 0L) throw e
                Log.w(TAG, "se cortó la conexión: ${e.message}")
                FALLO
            }
            if (leido == C.RESULT_END_OF_INPUT || leido == FALLO) {
                if (recibidoTotal == 0L) return C.RESULT_END_OF_INPUT
                reconectar()
                continue
            }
            if (leido <= 0) continue
            recibidoConexion += leido
            recibidoTotal += leido
            if (recibidoConexion > 256 * 1024) fallos = 0   // conexión sana
            salida = empalme.procesar(lectura, 0, leido)
            posSalida = 0
        }
    }

    private fun conectar(s: DataSpec) {
        val ds = fabrica.createDataSource()
        oyentes.forEach { ds.addTransferListener(it) }
        interno = ds
        recibidoConexion = 0
        ds.open(s)
    }

    /** Vuelve a conectar al mismo canal, con esperas crecientes. */
    private fun reconectar() {
        cerrarInterno()
        val s = spec ?: throw IOException("sin datos de conexión")
        while (true) {
            fallos++
            if (fallos > MAX_FALLOS) throw IOException("el canal no responde tras $MAX_FALLOS reconexiones")
            val espera = ESPERAS_MS[minOf(fallos - 1, ESPERAS_MS.size - 1)]
            if (espera > 0) {
                try {
                    Thread.sleep(espera)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("reconexión cancelada")
                }
            }
            if (cerrado) throw InterruptedIOException("fuente cerrada")
            Log.i(TAG, "reconectando el canal (intento $fallos)")
            empalme.nuevaConexion()
            try {
                conectar(s)
                return
            } catch (e: IOException) {
                if (esCancelacion(e)) throw e
                Log.w(TAG, "no se pudo reconectar: ${e.message}")
                cerrarInterno()
            }
        }
    }

    private fun esCancelacion(e: IOException): Boolean =
        cerrado || Thread.currentThread().isInterrupted ||
            (e is InterruptedIOException && e !is java.net.SocketTimeoutException)

    private fun cerrarInterno() {
        val ds = interno
        interno = null
        try {
            ds?.close()
        } catch (_: IOException) {
        }
    }

    override fun getUri(): Uri? = interno?.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        interno?.responseHeaders ?: emptyMap()

    override fun close() {
        cerrado = true
        cerrarInterno()
        salida = ByteArray(0)
        posSalida = 0
    }

    private companion object {
        const val TAG = "VivoContinuo"
        const val FALLO = -2
        const val MAX_FALLOS = 8
        val ESPERAS_MS = longArrayOf(0, 400, 1_000, 2_000, 3_000, 4_000)
    }
}
