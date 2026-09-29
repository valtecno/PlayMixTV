package com.miiptv.app.util

import android.content.Context
import android.os.Build
import com.miiptv.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guarda el último cierre inesperado en un archivo de texto, para poder
 * diagnosticarlo en aparatos donde no hay forma práctica de sacar un logcat
 * (sobre todo TV boxes sin ADB a mano).
 *
 * ---------------------------------------------------------------------------
 * POR QUÉ EXISTE
 *
 * Sin esto, un cierre en un equipo que el usuario no puede conectar a una PC
 * es una caja negra: solo queda "se cierra sola" como descripción del error.
 * Con esto, Ajustes → mantener presionado el número de versión (ver
 * SettingsActivity) muestra el texto exacto de la última excepción no
 * atrapada, con un botón para copiarlo, listo para pegar acá.
 *
 * No reemplaza un logcat real ni un servicio de reportes (Crashlytics y
 * similares): es deliberadamente simple, sin red y sin dependencias nuevas,
 * para poder instalarlo sin tocar nada más del proyecto.
 * ---------------------------------------------------------------------------
 */
object CrashLogger {

    private const val CARPETA = "logs"
    private const val ARCHIVO = "last_crash.txt"
    private const val PREFS = "miiptv_crashlog"
    private const val KEY_MOSTRADO_EN = "mostrado_en"

    /**
     * Instala el manejador. Se llama una sola vez, desde
     * [com.miiptv.app.PlayMixApp], antes de cualquier pantalla.
     *
     * Encadena al manejador anterior (el de Android, que es el que de verdad
     * termina el proceso) en vez de reemplazarlo: así el comportamiento ante
     * un cierre sigue siendo el de siempre, solo que ahora además queda el
     * archivo guardado.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val anterior = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Todo envuelto a propósito: si guardar el archivo fallara, no
            // puede impedir que el cierre siga su curso normal.
            runCatching { guardar(app, thread, error) }
            anterior?.uncaughtException(thread, error)
        }
    }

    private fun guardar(context: Context, thread: Thread, error: Throwable) {
        val carpeta = File(context.filesDir, CARPETA).apply { mkdirs() }
        val archivo = File(carpeta, ARCHIVO)

        val fecha = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val trazaEscrita = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()

        val texto = buildString {
            appendLine("Fecha: $fecha")
            appendLine("Versión app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Equipo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Hilo: ${thread.name}")
            appendLine()
            append(trazaEscrita)
        }

        // Se sobrescribe: solo importa el último cierre, no un historial.
        archivo.writeText(texto)
    }

    /** Texto del último cierre guardado, o null si no hay ninguno todavía. */
    fun lastCrash(context: Context): String? {
        val archivo = File(File(context.filesDir, CARPETA), ARCHIVO)
        return runCatching { archivo.takeIf { it.exists() }?.readText() }.getOrNull()
    }

    /**
     * Igual que [lastCrash], pero null si ese cierre ya se mostró una vez
     * (ver [marcarMostrado]) -para no repetir el mismo diálogo en cada
     * apertura, para siempre.
     *
     * Pensado para mostrarse SOLO, sin que el usuario tenga que navegar a
     * ningún lado (ver la llamada en MainActivity/LoginActivity.onCreate):
     * en equipos de TV donde la app se cierra tan rápido que no da tiempo
     * de llegar a Ajustes, es la única forma de que el diagnóstico se
     * alcance a ver siquiera una vez.
     */
    fun pendingCrash(context: Context): String? {
        val texto = lastCrash(context) ?: return null
        val archivo = File(File(context.filesDir, CARPETA), ARCHIVO)
        val cuandoOcurrio = archivo.lastModified()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val yaMostradoEn = prefs.getLong(KEY_MOSTRADO_EN, 0L)
        return if (cuandoOcurrio > yaMostradoEn) texto else null
    }

    /** Marca el cierre actual como ya mostrado, para que [pendingCrash] no lo repita. */
    fun marcarMostrado(context: Context) {
        val archivo = File(File(context.filesDir, CARPETA), ARCHIVO)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_MOSTRADO_EN, System.currentTimeMillis().coerceAtLeast(archivo.lastModified()))
            .apply()
    }
}
