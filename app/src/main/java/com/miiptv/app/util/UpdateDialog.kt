package com.miiptv.app.util

import android.app.Activity
import android.app.AlertDialog
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.Toast
import com.miiptv.app.R

/**
 * Presentación de las actualizaciones: avisa, descarga mostrando el avance y
 * lanza el instalador. La lógica de red vive en [Updater].
 */
object UpdateDialog {

    /**
     * Busca y actúa según el resultado.
     *
     * @param manual true cuando lo pidió el usuario desde Ajustes. En ese caso
     *        se le responde siempre (aunque esté al día); la búsqueda automática
     *        del arranque, en cambio, solo habla si hay algo nuevo.
     */
    fun check(activity: Activity, manual: Boolean) {
        val esperando = if (manual) {
            AlertDialog.Builder(activity)
                .setMessage(R.string.update_checking)
                .setCancelable(false)
                .show()
        } else null

        Updater.check(activity, force = manual) { resultado ->
            if (activity.isFinishing || activity.isDestroyed) return@check
            esperando?.dismiss()

            when (resultado) {
                is Updater.Result.Available -> {
                    // En automático se respeta el "ahora no" de esa versión
                    if (!manual && Updater.isSkipped(activity, resultado.release.version)) return@check
                    ofrecer(activity, resultado.release)
                }
                Updater.Result.UpToDate ->
                    if (manual) toast(activity, R.string.update_up_to_date)
                Updater.Result.NotConfigured ->
                    if (manual) toast(activity, R.string.update_not_configured)
                is Updater.Result.Failed ->
                    if (manual) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.update_failed, resultado.reason),
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }
        }
    }

    private fun toast(activity: Activity, res: Int) =
        Toast.makeText(activity, res, Toast.LENGTH_SHORT).show()

    private fun ofrecer(activity: Activity, release: Updater.Release) {
        // Vanessa se presenta y da el mensaje de marca; ya no se muestra debajo
        // la versión ni el changelog (referencias a GitHub/repo), que era
        // información técnica sin sentido para quien usa la app.
        val vista = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_update_available, null, false)

        AlertDialog.Builder(activity)
            .setTitle(R.string.update_available_title)
            .setView(vista)
            .setPositiveButton(R.string.update_download) { _, _ -> descargar(activity, release) }
            .setNegativeButton(R.string.update_later) { _, _ ->
                Updater.skip(activity, release.version)
            }
            .show()
    }

    private fun descargar(activity: Activity, release: Updater.Release) {
        val barra = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            setPadding(48, 32, 48, 16)
        }

        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.update_downloading)
            .setView(barra)
            .setCancelable(false)
            .show()

        Updater.download(
            activity,
            release,
            onProgress = { pct ->
                if (activity.isFinishing) return@download
                if (pct < 0) {
                    barra.isIndeterminate = true
                } else {
                    barra.isIndeterminate = false
                    barra.progress = pct
                    // Al llegar al 100% toma el relevo el instalador del sistema
                    if (pct >= 100) dialogo.dismiss()
                }
            },
            onError = { motivo ->
                if (activity.isFinishing) return@download
                dialogo.dismiss()
                val texto = when (motivo) {
                    "permiso_instalacion" -> activity.getString(R.string.update_need_permission)
                    "verificación_fallida" -> activity.getString(R.string.update_verification_failed)
                    else -> activity.getString(R.string.update_failed, motivo)
                }
                Toast.makeText(activity, texto, Toast.LENGTH_LONG).show()
            }
        )
    }
}
