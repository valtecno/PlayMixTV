package com.miiptv.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.miiptv.app.R
import com.miiptv.app.api.Session
import com.miiptv.app.databinding.ActivitySettingsBinding
import com.miiptv.app.databinding.DialogAccountsBinding
import com.miiptv.app.databinding.DialogAudioBinding
import com.miiptv.app.databinding.ItemAccountBinding
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.RemoteControl
import com.miiptv.app.util.Accounts
import com.miiptv.app.util.Catalog
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.util.Servers
import com.miiptv.app.util.PlayerPrefs
import com.miiptv.app.util.ServerDiagnostics
import com.miiptv.app.util.UpdateDialog

/**
 * Ajustes del sistema: opciones internas del reproductor, del catálogo y de la cuenta.
 * Se abre con el ícono de engranaje de la barra superior.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // ---------- Reproductor ----------
        binding.rowBuffer.setOnClickListener { pickBuffer() }
        binding.rowAspect.setOnClickListener { pickAspect() }

        binding.swReconnect.isChecked = PlayerPrefs.getAutoReconnect(this)
        binding.swReconnect.setOnCheckedChangeListener { _, checked ->
            PlayerPrefs.setAutoReconnect(this, checked)
        }
        binding.rowReconnect.setOnClickListener { binding.swReconnect.toggle() }

        binding.swBackground.isChecked = PlayerPrefs.getBackground(this)
        binding.swBackground.setOnCheckedChangeListener { _, checked ->
            PlayerPrefs.setBackground(this, checked)
        }
        binding.rowBackground.setOnClickListener { binding.swBackground.toggle() }

        binding.swAutoNext.isChecked = PlayerPrefs.getAutoPlayNext(this)
        binding.swAutoNext.setOnCheckedChangeListener { _, checked ->
            PlayerPrefs.setAutoPlayNext(this, checked)
        }
        binding.rowAutoNext.setOnClickListener { binding.swAutoNext.toggle() }

        binding.swScreenOn.isChecked = PlayerPrefs.getKeepScreenOn(this)
        binding.swScreenOn.setOnCheckedChangeListener { _, checked ->
            PlayerPrefs.setKeepScreenOn(this, checked)
        }
        binding.rowScreenOn.setOnClickListener { binding.swScreenOn.toggle() }

        // ---------- Contenido ----------
        binding.rowDeviceMode.setOnClickListener {
            startActivity(
                Intent(this, DeviceModeActivity::class.java)
                    .putExtra(DeviceModeActivity.EXTRA_ONLY_CHANGE, true)
            )
        }

        binding.rowPersonalize.setOnClickListener {
            startActivity(Intent(this, PersonalizeActivity::class.java))
        }

        // La acción vive ahora en su propio botón; el recuadro de abajo solo
        // muestra el recuento y, con pulsación larga, abre el diagnóstico.
        binding.rowUpdate.setOnClickListener { UpdateDialog.check(this, manual = true) }
        binding.tvUpdateState.text = getString(R.string.settings_version, appVersion())

        binding.btnRefreshCatalog.setOnClickListener { refreshCatalog() }
        binding.rowRefresh.setOnClickListener { refreshCatalog() }
        // Pulsación larga: prueba el panel endpoint por endpoint y dice qué falla
        binding.rowRefresh.setOnLongClickListener { runDiagnostics(); true }
        binding.rowClearCache.setOnClickListener { clearImageCache() }

        // ---------- Cuenta ----------
        binding.tvServer.text = Session.server(this)
            .takeIf { it.isNotBlank() }
            ?.let { Servers.labelFor(it) } ?: "—"
        binding.tvUser.text = Session.username(this).ifBlank { "—" }
        binding.rowSwitchAccount.setOnClickListener { switchAccount() }
        binding.rowAudio.setOnClickListener { showAudioDialog() }
        binding.btnLogout.setOnClickListener { confirmLogout() }

        /*
         * Resalte del foco con control remoto.
         *
         * Esta pantalla se arma con filas que comparten el estilo SettingsRow,
         * que trae un fondo fijo sin estado enfocado: moverse por ella con el
         * mando no cambiaba un solo pixel.
         *
         * Se resuelve recorriendo el árbol en vez de listar los ids uno por uno,
         * así una fila que se agregue mañana hereda el resalte sin tocar nada.
         *
         * TIENE QUE IR AL FINAL de onCreate: el recorrido usa
         * hasOnClickListeners() para descartar las filas que solo muestran un
         * dato (servidor, usuario), y esos listeners se asignan justo arriba.
         */
        RemoteControl.applyFocusToTree(binding.root, RemoteControl.isEnabled(this))

        binding.tvVersion.text = getString(R.string.settings_version, appVersion())

        refreshLabels()
    }

    /** Vista que tenía el foco justo antes de salir a otra pantalla (Cuentas → Agregar, etc.). */
    private var focoAntesDeSalir: View? = null

    override fun onPause() {
        super.onPause()
        focoAntesDeSalir = currentFocus
    }

    override fun onResume() {
        super.onResume()
        // Al volver de "Agregar cuenta" (el diálogo de Cuentas ya se había
        // cerrado antes de salir, así que no hay a dónde volver dentro de
        // él) esta pantalla se quedaba sin nada visualmente seleccionado:
        // igual síntoma que en la pantalla principal al volver de un canal
        // en pantalla completa. Se le devuelve el foco a la fila donde
        // estaba parado el control remoto.
        val vista = focoAntesDeSalir
        focoAntesDeSalir = null
        if (RemoteControl.isEnabled(this) && currentFocus == null && vista?.isAttachedToWindow == true) {
            RemoteControl.focusWhenReady(vista)
        }
    }

    private fun refreshLabels() {
        binding.tvDeviceMode.text = DeviceMode.label(this, DeviceMode.get(this))

        val otras = Accounts.others(this).size
        binding.tvAccountCount.text =
            if (otras > 0) getString(R.string.accounts_available, otras) else getString(R.string.add_account)

        binding.tvBuffer.text = PlayerPrefs.bufferLabel(this, PlayerPrefs.getBuffer(this))
        binding.tvAspect.text = PlayerPrefs.aspectLabel(this, PlayerPrefs.getAspect(this))
        binding.tvCatalog.text = if (Catalog.isEmpty) {
            getString(R.string.catalog_empty)
        } else {
            getString(R.string.catalog_count, Catalog.live.size, Catalog.movies.size, Catalog.series.size)
        }
    }

    /**
     * Permite saltar entre la cuenta de Sistema L y la de Sistema XL sin volver
     * a escribir la contraseña. Si no hay otra guardada, abre el login.
     */
    /**
     * Diálogo propio (no el de lista pelada del sistema): muestra cada cuenta
     * como tarjeta, marca la activa, y "Agregar otra cuenta" es un botón real
     * con el degradado de la app, no un texto suelto.
     */
    private fun switchAccount() {
        val vista = DialogAccountsBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(vista.root).create()

        val activa = Accounts.getAll(this).firstOrNull {
            it.serverUrl.removeSuffix("/") == Session.server(this).removeSuffix("/") &&
                it.username == Session.username(this)
        }
        val todas = Accounts.getAll(this)

        vista.tvAccountsHint.text = if (todas.size <= 1) {
            getString(R.string.accounts_hint_single)
        } else {
            getString(R.string.accounts_hint_multi, todas.size)
        }

        todas.forEach { cuenta ->
            val fila = ItemAccountBinding.inflate(layoutInflater, vista.accountsContainer, false)
            fila.tvAccountUser.text = cuenta.username
            fila.tvAccountServer.text = cuenta.serverLabel
            fila.tvAccountAvatar.text = cuenta.username.take(1).uppercase()

            val esActiva = cuenta == activa
            // Sin esto la fila se ve exactamente igual con foco que sin foco:
            // con el control remoto no hay manera de saber dónde está parado,
            // ni siquiera en la cuenta activa (que también es alcanzable,
            // aunque tocarla no haga nada).
            fila.root.background = Appearance.withFocusState(
                this, ContextCompat.getDrawable(this, R.drawable.bg_option)!!, 12f
            )
            if (esActiva) {
                fila.tvAccountBadge.visibility = View.VISIBLE
                fila.tvAccountBadge.text = getString(R.string.account_active)
                fila.tvAccountBadge.background = Appearance.gradient(this, 14f)
                fila.root.alpha = 1f
            } else {
                fila.tvAccountBadge.visibility = View.GONE
                fila.root.alpha = 0.75f
                fila.root.setOnClickListener {
                    dialog.dismiss()
                    useAccount(cuenta)
                }
            }
            vista.accountsContainer.addView(fila.root)
        }

        vista.btnAddAccount.background = Appearance.withFocusState(this, Appearance.gradient(this, 12f), 12f)
        vista.btnAddAccount.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, LoginActivity::class.java))
        }
        vista.btnCloseAccounts.background = Appearance.withFocusState(
            this, ContextCompat.getDrawable(this, R.drawable.bg_option)!!, 12f
        )
        vista.btnCloseAccounts.setOnClickListener { dialog.dismiss() }

        dialog.show()
        // Sin esto el diálogo abre sin nada enfocado: recién se ve algo
        // seleccionado después de mover el control remoto por primera vez.
        if (RemoteControl.isEnabled(this)) {
            RemoteControl.focusWhenReady(vista.accountsContainer.getChildAt(0) ?: vista.btnAddAccount)
        }
    }

    private fun useAccount(cuenta: Accounts.Account) {
        Session.save(this, cuenta.serverUrl, cuenta.username, cuenta.password)
        Catalog.clear()
        Toast.makeText(
            this, getString(R.string.account_switched, cuenta.serverLabel), Toast.LENGTH_SHORT
        ).show()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun pickBuffer() {
        val options = arrayOf(
            PlayerPrefs.bufferLabel(this, PlayerPrefs.BUFFER_LOW),
            PlayerPrefs.bufferLabel(this, PlayerPrefs.BUFFER_NORMAL),
            PlayerPrefs.bufferLabel(this, PlayerPrefs.BUFFER_HIGH)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_buffer)
            .setSingleChoiceItems(options, PlayerPrefs.getBuffer(this)) { dialog, which ->
                PlayerPrefs.setBuffer(this, which)
                refreshLabels()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickAspect() {
        val options = arrayOf(
            PlayerPrefs.aspectLabel(this, PlayerPrefs.FIT),
            PlayerPrefs.aspectLabel(this, PlayerPrefs.CROP),
            PlayerPrefs.aspectLabel(this, PlayerPrefs.STRETCH)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_aspect)
            .setSingleChoiceItems(options, PlayerPrefs.getAspect(this)) { dialog, which ->
                PlayerPrefs.setAspect(this, which)
                refreshLabels()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAudioDialog() {
        val vista = DialogAudioBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(vista.root).create()

        fun refrescarValores() {
            vista.tvAudioLanguage.text = PlayerPrefs.audioLanguageLabel(this, PlayerPrefs.getAudioLanguage(this))
            vista.tvSubtitles.text = PlayerPrefs.subtitleModeLabel(this, PlayerPrefs.getSubtitleMode(this))
        }
        refrescarValores()

        vista.btnNormalizeVolume.background = Appearance.gradient(this, 12f)
        vista.btnNormalizeVolume.setOnClickListener { normalizeSystemVolume() }

        vista.rowAudioLanguage.setOnClickListener { pickAudioLanguage { refrescarValores() } }
        vista.rowSubtitles.setOnClickListener { pickSubtitles { refrescarValores() } }
        vista.btnCloseAudio.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    /**
     * Sube (o baja) el volumen del STREAM_MUSIC de Android a un nivel parejo.
     * Es el volumen del sistema, no el del reproductor -- ese ya tiene su
     * propia barra dentro de Canales/PPV (ver PlayerActivity.setupVolumeControl).
     * Sirve para cuando algún canal dejó el volumen del aparato en un
     * extremo raro y hay que arrancar de nuevo con algo razonable.
     */
    private fun normalizeSystemVolume() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val maximo = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val objetivo = (maximo * 0.7f).toInt().coerceIn(1, maximo)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, objetivo, AudioManager.FLAG_SHOW_UI)
        Toast.makeText(this, R.string.audio_volume_normalized, Toast.LENGTH_SHORT).show()
    }

    private fun pickAudioLanguage(onChanged: () -> Unit) {
        val codigos = arrayOf<String?>(null, "spa", "eng")
        val labels = codigos.map { PlayerPrefs.audioLanguageLabel(this, it) }.toTypedArray()
        val actual = codigos.indexOf(PlayerPrefs.getAudioLanguage(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.audio_language_label)
            .setSingleChoiceItems(labels, actual) { dialog, which ->
                PlayerPrefs.setAudioLanguage(this, codigos[which])
                onChanged()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickSubtitles(onChanged: () -> Unit) {
        val modos = intArrayOf(
            PlayerPrefs.SUB_OFF, PlayerPrefs.SUB_SPANISH, PlayerPrefs.SUB_ENGLISH, PlayerPrefs.SUB_AUTO
        )
        val labels = modos.map { PlayerPrefs.subtitleModeLabel(this, it) }.toTypedArray()
        val actual = modos.indexOf(PlayerPrefs.getSubtitleMode(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.subtitle_label)
            .setSingleChoiceItems(labels, actual) { dialog, which ->
                PlayerPrefs.setSubtitleMode(this, modos[which])
                onChanged()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshCatalog() {
        Toast.makeText(this, R.string.catalog_refreshing, Toast.LENGTH_SHORT).show()
        Catalog.ensureLoaded(this, force = true) { stillLoading ->
            if (!isFinishing && !isDestroyed && !stillLoading) {
                refreshLabels()
                Toast.makeText(this, R.string.catalog_refreshed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Prueba directa contra el panel para ver qué endpoint falla y por qué. */
    private fun runDiagnostics() {
        val esperando = AlertDialog.Builder(this)
            .setTitle(R.string.diag_title)
            .setMessage(R.string.diag_running)
            .setCancelable(false)
            .show()

        ServerDiagnostics.run(this) { informe ->
            if (isFinishing || isDestroyed) return@run
            esperando.dismiss()
            AlertDialog.Builder(this)
                .setTitle(R.string.diag_title)
                .setMessage(informe)
                .setPositiveButton(R.string.diag_copy) { _, _ ->
                    val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("PlayMix diag", informe))
                    Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun clearImageCache() {
        // Solo se borra la caché en disco: apagar la instancia de Picasso la dejaría
        // inutilizable para el resto de la app hasta reiniciarla.
        // También se tira el JSON del panel guardado por OkHttp, en un hilo aparte
        // porque borrar archivos en el hilo principal traba la pantalla.
        Thread {
            runCatching { Session.dropHttpCache(this) }
            runCatching { cacheDir.deleteRecursively() }
        }.start()
        Toast.makeText(this, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_logout)
            .setMessage(R.string.logout_confirm)
            .setPositiveButton(R.string.setting_logout) { _, _ ->
                Session.logout(this)
                Catalog.clear()
                startActivity(
                    Intent(this, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    }.getOrDefault("1.0")
}
