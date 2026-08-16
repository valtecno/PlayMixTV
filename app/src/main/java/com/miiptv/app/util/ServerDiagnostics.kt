package com.miiptv.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.miiptv.app.api.Session
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Prueba directa contra el panel, endpoint por endpoint, para saber POR QUÉ no
 * carga el contenido en vez de adivinarlo.
 *
 * Mide tres cosas por cada llamada:
 *  - TTFB: cuánto tarda el panel en mandar el primer byte. Es el número que
 *    importa. Si get_vod_streams tiene un TTFB de 30 s, cualquier cliente con
 *    el timeout por defecto de 10 s lo va a dar por caído.
 *  - Peso: cuántos MB devuelve. Un listado de 80 MB explica solo el consumo de
 *    memoria y el tiempo de espera.
 *  - Forma: si empieza con `[` (lista, correcto) o con `{` (objeto, que en
 *    Xtream significa error de cuenta / límite de conexiones).
 *
 * El cuerpo se lee en bloques y se descarta: nunca se guarda entero en memoria,
 * así que el diagnóstico se puede correr aunque el catálogo no entre en el heap.
 */
object ServerDiagnostics {

    private val ui = Handler(Looper.getMainLooper())

    data class Probe(
        val nombre: String,
        val ok: Boolean,
        val httpCode: Int,
        val ttfbMs: Long,
        val totalMs: Long,
        val bytes: Long,
        val forma: Char?,
        val items: Int,
        val error: String?
    ) {
        val mb: String get() = String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }

    /** Corre el diagnóstico en segundo plano y devuelve el informe ya formateado. */
    fun run(context: Context, onResult: (String) -> Unit) {
        val ctx = context.applicationContext
        Thread {
            val informe = buildReport(ctx)
            ui.post { onResult(informe) }
        }.start()
    }

    private fun buildReport(context: Context): String {
        val base = Session.server(context).trim().trimEnd('/')
        val user = Session.username(context)
        val pass = Session.password(context)

        if (base.isBlank() || user.isBlank()) return "No hay sesión iniciada."

        val sistema = Servers.labelFor(base)
        val sb = StringBuilder()
        sb.append("Sistema: ").append(sistema).append('\n')
        sb.append("Usuario: ").append(user).append("\n\n")

        val pruebas = listOf(
            "Login" to "",
            "Categorías canales" to "&action=get_live_categories",
            "Categorías pelis" to "&action=get_vod_categories",
            "Categorías series" to "&action=get_series_categories",
            "Canales (todo)" to "&action=get_live_streams",
            "Películas (todo)" to "&action=get_vod_streams",
            "Series (todo)" to "&action=get_series"
        )

        var lento = false
        var pesado = false
        var objeto = false

        for ((nombre, extra) in pruebas) {
            val url = "$base/player_api.php?username=$user&password=$pass$extra"
            val p = probe(nombre, url)
            sb.append(format(p)).append('\n')
            if (p.ttfbMs > 10_000) lento = true
            if (p.bytes > 25L * 1024 * 1024) pesado = true
            if (p.forma == '{' && extra.isNotEmpty()) objeto = true
        }

        sb.append("\n--- Lectura ---\n")
        when {
            objeto -> sb.append(
                "El panel devuelve un objeto donde debería ir una lista. " +
                    "Suele ser cuenta vencida o límite de conexiones simultáneas alcanzado.\n"
            )
            lento -> sb.append(
                "Hay endpoints que tardan más de 10 s en responder el primer byte. " +
                    "Con el timeout por defecto de OkHttp (10 s) esas llamadas fallaban siempre.\n"
            )
            pesado -> sb.append(
                "Los listados completos superan los 25 MB. Conviene no pedirlos en paralelo " +
                    "y apoyarse en la caché de disco.\n"
            )
            else -> sb.append("El panel responde dentro de lo esperado en todos los endpoints.\n")
        }
        return sb.toString()
    }

    private fun format(p: Probe): String = buildString {
        append(if (p.ok) "OK  " else "FALLA  ")
        append(p.nombre).append('\n')
        if (p.error != null) {
            append("     ").append(p.error).append('\n')
            return@buildString
        }
        append("     HTTP ").append(p.httpCode)
        append(" · 1er byte ").append(p.ttfbMs).append(" ms")
        append(" · total ").append(p.totalMs).append(" ms")
        append(" · ").append(p.mb)
        if (p.items > 0) append(" · ~").append(p.items).append(" ítems")
        if (p.forma == '{') append(" · ¡objeto, no lista!")
        append('\n')
    }

    private fun probe(nombre: String, url: String): Probe {
        // Cliente propio con timeout largo: la idea es MEDIR cuánto tarda, no cortarlo.
        val cliente = Session.httpClient.newBuilder()
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .cache(null)
            .build()

        val inicio = System.currentTimeMillis()
        return try {
            cliente.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val ttfb = System.currentTimeMillis() - inicio
                val source = r.body?.source()
                var bytes = 0L
                var forma: Char? = null
                var comas = 0
                if (source != null) {
                    val buffer = ByteArray(64 * 1024)
                    val stream = source.inputStream()
                    while (true) {
                        val leidos = stream.read(buffer)
                        if (leidos <= 0) break
                        if (forma == null) {
                            // Primer carácter no vacío: dice si es lista u objeto
                            for (i in 0 until leidos) {
                                val c = Char(buffer[i].toInt() and 0xFF)
                                if (!c.isWhitespace()) { forma = c; break }
                            }
                        }
                        // Contar '}' aproxima la cantidad de registros del listado
                        for (i in 0 until leidos) if (buffer[i] == '}'.code.toByte()) comas++
                        bytes += leidos
                    }
                }
                Probe(
                    nombre = nombre,
                    ok = r.isSuccessful && forma != '{',
                    httpCode = r.code,
                    ttfbMs = ttfb,
                    totalMs = System.currentTimeMillis() - inicio,
                    bytes = bytes,
                    forma = forma,
                    items = if (forma == '[') comas else 0,
                    error = null
                )
            }
        } catch (t: Throwable) {
            Probe(
                nombre = nombre,
                ok = false,
                httpCode = 0,
                ttfbMs = System.currentTimeMillis() - inicio,
                totalMs = System.currentTimeMillis() - inicio,
                bytes = 0,
                forma = null,
                items = 0,
                error = t::class.java.simpleName + ": " + (t.message ?: "sin detalle")
            )
        }
    }
}
