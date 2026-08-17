package com.miiptv.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import com.miiptv.app.BuildConfig
import com.miiptv.app.api.Session
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Actualizaciones desde las Releases de GitHub.
 *
 * Consulta la última versión publicada en el repositorio configurado
 * (gradle.properties → playmix.repo), la compara con la instalada y, si hay una
 * más nueva, descarga el APK y lanza el instalador del sistema.
 *
 * ---------------------------------------------------------------------------
 * POR QUÉ RELEASES Y NO LOS ARTIFACTS DE ACTIONS
 *
 * El workflow que ya existía subía el APK como "artifact". Un artifact solo se
 * puede descargar autenticado con una cuenta que tenga acceso al repositorio,
 * así que la app no puede bajarlo. Las Releases, en cambio, tienen una URL
 * pública directa. Por eso se añadió el workflow release.yml.
 *
 * AVISO IMPORTANTE SOBRE LA FIRMA
 *
 * Android solo deja actualizar una app si el APK nuevo está firmado con la
 * MISMA clave que el instalado. Si cada compilación usa un keystore de depuración
 * distinto (lo que pasa por defecto en GitHub Actions, que genera uno nuevo en
 * cada ejecución), la instalación falla con "aplicación no instalada" aunque la
 * descarga haya ido bien. release.yml usa un keystore guardado en los secrets
 * del repositorio justamente para evitarlo.
 * ---------------------------------------------------------------------------
 */
object Updater {

    private const val PREFS = "miiptv_updater"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SKIPPED = "skipped_version"

    /** Cada cuánto se busca sola una actualización al abrir la app. */
    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    private val ui = Handler(Looper.getMainLooper())

    data class Release(
        val version: String,
        val apkUrl: String,
        val notes: String,
        val sizeBytes: Long
    )

    sealed class Result {
        data class Available(val release: Release) : Result()
        object UpToDate : Result()
        object NotConfigured : Result()
        data class Failed(val reason: String) : Result()
    }

    /** ¿Hay un repositorio configurado en gradle.properties? */
    val isConfigured: Boolean get() = BuildConfig.GITHUB_REPO.isNotBlank()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Busca en segundo plano y responde en el hilo principal.
     *
     * @param force ignora el intervalo de espera. Es lo que usa el botón manual;
     *              la búsqueda automática del arranque lo deja en false para no
     *              consultar GitHub en cada apertura.
     */
    fun check(context: Context, force: Boolean, onResult: (Result) -> Unit) {
        val ctx = context.applicationContext
        if (!isConfigured) {
            onResult(Result.NotConfigured)
            return
        }
        if (!force) {
            val ultima = prefs(ctx).getLong(KEY_LAST_CHECK, 0L)
            if (System.currentTimeMillis() - ultima < CHECK_INTERVAL_MS) {
                onResult(Result.UpToDate)
                return
            }
        }

        Thread {
            val resultado = consultar(ctx)
            prefs(ctx).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            ui.post { onResult(resultado) }
        }.start()
    }

    private fun consultar(context: Context): Result {
        val url = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"
        return try {
            val peticion = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .build()

            Session.httpClient.newCall(peticion).execute().use { r ->
                if (r.code == 404) return Result.Failed("sin versiones publicadas")
                if (!r.isSuccessful) return Result.Failed("HTTP ${r.code}")

                val json = JSONObject(r.body?.string().orEmpty())
                val etiqueta = json.optString("tag_name").ifBlank { json.optString("name") }
                val assets = json.optJSONArray("assets")

                var apk = ""
                var tamano = 0L
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apk = a.optString("browser_download_url")
                            tamano = a.optLong("size")
                            break
                        }
                    }
                }
                if (etiqueta.isBlank() || apk.isBlank()) {
                    return Result.Failed("la versión publicada no trae APK")
                }

                val nueva = normalizar(etiqueta)
                if (compare(nueva, normalizar(BuildConfig.VERSION_NAME)) <= 0) {
                    Result.UpToDate
                } else {
                    Result.Available(
                        Release(nueva, apk, json.optString("body").trim(), tamano)
                    )
                }
            }
        } catch (t: Throwable) {
            Result.Failed(t::class.java.simpleName)
        }
    }

    /** "v1.4.2" y "V1.4.2 " son la misma versión que "1.4.2". */
    private fun normalizar(v: String): String = v.trim().trimStart('v', 'V').trim()

    /**
     * Compara versiones por tramos numéricos: 1.10 es MAYOR que 1.9, cosa que
     * una comparación de texto entendería al revés.
     */
    private fun compare(a: String, b: String): Int {
        val pa = a.split('.', '-')
        val pb = b.split('.', '-')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val na = pa.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val nb = pb.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            if (na != nb) return na - nb
        }
        return 0
    }

    // ---------------- Descarga e instalación ----------------

    /**
     * Descarga el APK informando el avance y, al terminar, abre el instalador.
     * [onProgress] recibe 0..100, o -1 si el servidor no declara el tamaño.
     */
    fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        val ctx = context.applicationContext
        Thread {
            // Sin returns con etiqueta dentro del lambda del Thread: se resuelve
            // todo con un resultado nulo/no nulo, que es más claro y no depende
            // de cómo Kotlin etiquete un lambda pasado a un constructor Java.
            var fallo: String? = null
            var descargado: File? = null

            try {
                val carpeta = File(ctx.cacheDir, "updates").apply { mkdirs() }
                // Se limpian descargas anteriores: si no, se acumulan APKs completos
                carpeta.listFiles()?.forEach { it.delete() }
                val destino = File(carpeta, "PlayMixTV-${release.version}.apk")

                val peticion = Request.Builder().url(release.apkUrl).build()
                Session.httpClient.newCall(peticion).execute().use { r ->
                    val cuerpo = r.body
                    when {
                        !r.isSuccessful -> fallo = "HTTP ${r.code}"
                        cuerpo == null -> fallo = "respuesta vacía"
                        else -> {
                            val total =
                                if (release.sizeBytes > 0) release.sizeBytes else cuerpo.contentLength()
                            var leidos = 0L
                            var ultimoPct = -1

                            cuerpo.byteStream().use { entrada ->
                                destino.outputStream().use { salida ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val n = entrada.read(buffer)
                                        if (n <= 0) break
                                        salida.write(buffer, 0, n)
                                        leidos += n
                                        if (total > 0) {
                                            val pct = (leidos * 100 / total).toInt()
                                            // Avisar solo al cambiar de punto porcentual
                                            if (pct != ultimoPct) {
                                                ultimoPct = pct
                                                ui.post { onProgress(pct) }
                                            }
                                        } else {
                                            ui.post { onProgress(-1) }
                                        }
                                    }
                                }
                            }
                            descargado = destino
                        }
                    }
                }
            } catch (t: Throwable) {
                fallo = t::class.java.simpleName
            }

            val motivo = fallo
            val apk = descargado
            ui.post {
                when {
                    motivo != null -> onError(motivo)
                    apk != null -> install(ctx, apk, onError)
                    else -> onError("descarga incompleta")
                }
            }
        }.start()
    }

    /**
     * Abre el instalador del sistema. En Android 8 o superior la app necesita
     * permiso explícito para instalar; si no lo tiene, se lleva al usuario a la
     * pantalla donde concederlo en vez de fallar en silencio.
     */
    fun install(context: Context, apk: File, onError: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { onError(it::class.java.simpleName) }
            onError("permiso_instalacion")
            return
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { onError(it::class.java.simpleName) }
    }

    /** El usuario dijo "ahora no" para esta versión: no volver a molestar con ella. */
    fun skip(context: Context, version: String) {
        prefs(context).edit().putString(KEY_SKIPPED, version).apply()
    }

    fun isSkipped(context: Context, version: String): Boolean =
        prefs(context).getString(KEY_SKIPPED, null) == version
}
