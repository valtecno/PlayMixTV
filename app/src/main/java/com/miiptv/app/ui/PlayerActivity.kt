package com.miiptv.app.ui

import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.miiptv.app.R
import com.miiptv.app.api.ContentItem
import com.miiptv.app.api.ContentType
import com.miiptv.app.databinding.ActivityPlayerBinding
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.Favorites
import com.miiptv.app.util.PlayerFactory
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.util.PlaybackHolder
import com.miiptv.app.util.PlayerPrefs
import com.miiptv.app.service.PlaybackService
import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"

        // Datos del contenido, para poder marcarlo como favorito desde el reproductor
        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_ITEM_ICON = "extra_item_icon"
        const val EXTRA_ITEM_CATEGORY = "extra_item_category"
        const val EXTRA_ITEM_TYPE = "extra_item_type"
        const val EXTRA_ITEM_EXT = "extra_item_ext"

        // Lista de episodios, para encadenar la reproducción automática
        const val EXTRA_PLAYLIST_URLS = "extra_playlist_urls"
        const val EXTRA_PLAYLIST_TITLES = "extra_playlist_titles"
        const val EXTRA_PLAYLIST_INDEX = "extra_playlist_index"

        private const val MAX_RETRIES = 5
        private const val NEXT_COUNTDOWN_SECONDS = 8
        private const val ACTION_PIP_PLAY_PAUSE = "com.miiptv.app.PIP_PLAY_PAUSE"
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    private var streamUrl: String = ""
    private var contentTitle: String = ""
    private var favoriteItem: ContentItem? = null
    private var retries = 0
    /** Evita repetir el aviso de audio incompatible en cada cambio de pistas. */
    private var audioAvisado = false

    /** Episodios encadenados (vacío si no viene de una serie). */
    private var playlistUrls: List<String> = emptyList()
    private var playlistTitles: List<String> = emptyList()
    private var playlistIndex = 0

    private var locked = false
    private val ui = Handler(Looper.getMainLooper())
    private var countdown = 0
    private var lastUnlockTap = 0L

    /** Botón play/pausa que se ve en la ventanita de PiP (se registra una sola vez). */
    private var pipReceiverRegistered = false
    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PIP_PLAY_PAUSE) return
            val exo = player ?: return
            if (exo.isPlaying) exo.pause() else exo.play()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                buildPipParams()?.let { setPictureInPictureParams(it) }
            }
        }
    }

    private val countdownTick = object : Runnable {
        override fun run() {
            countdown--
            if (countdown <= 0) {
                hideNextBar()
                playNextEpisode()
            } else {
                binding.tvNextCountdown.text = getString(R.string.player_next_in, countdown)
                ui.postDelayed(this, 1000L)
            }
        }
    }

    // ---------------- Ciclo de vida ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamUrl = intent.getStringExtra(EXTRA_URL) ?: return finish()
        contentTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        // En móvil el resto de la app queda bloqueada en vertical (ver DeviceMode);
        // el reproductor es la única pantalla que pasa a horizontal, y lo hace solo
        // apenas se reproduce contenido. Al salir de acá, la pantalla anterior ya
        // está fijada en vertical y el sistema vuelve a esa orientación solo.
        // SENSOR_LANDSCAPE deja girar entre horizontal-izquierda/derecha según se
        // gire el celular, pero nunca cae en vertical.
        requestedOrientation = if (DeviceMode.isMobile(this)) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        readPlaylist()
        readFavoriteItem()

        binding.tvNowPlaying.text = contentTitle
        binding.tvNowPlaying.isSelected = true   // activa el desplazamiento del texto largo

        if (PlayerPrefs.getKeepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        applyAspect()

        binding.playerView.subtitleView?.setFixedTextSize(
            TypedValue.COMPLEX_UNIT_SP, Appearance.getSubtitleSize(this).toFloat()
        )

        setupControls()
        askNotificationPermission()
        registerPipReceiver()

        if (PlaybackHolder.canResume(streamUrl)) {
            // Vuelve desde la notificación: se engancha al reproductor que ya está sonando
            PlaybackService.stop(this)
            player = PlaybackHolder.player
            binding.playerView.player = player
        } else {
            PlaybackHolder.release()
            startPlayback(streamUrl, resumeAtMs = 0L)
        }
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(countdownTick)

        val seguirSonando = PlayerPrefs.getBackground(this) && !isFinishing && player?.playWhenReady == true
        if (seguirSonando) {
            // El servicio en primer plano lo mantiene vivo; la pantalla suelta la vista
            PlaybackHolder.currentTitle = contentTitle
            binding.playerView.player = null
            PlaybackService.start(this)
        } else {
            PlaybackService.stop(this)
            PlaybackHolder.release()
            player = null
        }
    }

    override fun onStart() {
        super.onStart()
        val vivo = PlaybackHolder.player
        when {
            // Seguía sonando en segundo plano: se retoma sin reiniciar el stream
            vivo != null && PlaybackHolder.canResume(streamUrl) -> {
                PlaybackService.stop(this)
                player = vivo
                binding.playerView.player = vivo
            }
            // Se detuvo desde la notificación: hay que rearmarlo, el anterior ya no sirve
            vivo == null && !isFinishing -> {
                player = null
                startPlayback(streamUrl, resumeAtMs = 0L)
            }
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        unregisterPipReceiver()
        if (isFinishing) {
            PlaybackService.stop(this)
            PlaybackHolder.release()
            player = null
        }
        super.onDestroy()
    }

    // ---------------- Picture-in-Picture ----------------
    // Al salir de la app (botón Inicio, cambiar de app, etc.) mientras hay video
    // reproduciéndose, se abre una ventanita flotante encima de las demás apps,
    // igual que hace YouTube, para poder seguir mirando mientras se hace otra cosa.

    private fun canUsePip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            DeviceMode.isMobile(this) &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun registerPipReceiver() {
        if (!canUsePip() || pipReceiverRegistered) return
        val filter = IntentFilter(ACTION_PIP_PLAY_PAUSE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, pipReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pipReceiver, filter)
        }
        pipReceiverRegistered = true
    }

    private fun unregisterPipReceiver() {
        if (!pipReceiverRegistered) return
        runCatching { unregisterReceiver(pipReceiver) }
        pipReceiverRegistered = false
    }

    /** El aspecto de PiP en Android tiene que estar entre 1:2.39 y 2.39:1. */
    private fun clampedAspect(width: Int, height: Int): Rational {
        val r = Rational(width, height)
        val value = r.toFloat()
        return when {
            value > 2.39f -> Rational(239, 100)
            value < 1f / 2.39f -> Rational(100, 239)
            else -> r
        }
    }

    private fun buildPipParams(): PictureInPictureParams? {
        if (!canUsePip()) return null
        val playing = player?.isPlaying == true
        val icon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val titulo = getString(R.string.player_play_pause)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName), flags
        )
        val accion = RemoteAction(Icon.createWithResource(this, icon), titulo, titulo, pi)

        val vf = player?.videoFormat
        val aspecto = if (vf != null && vf.width > 0 && vf.height > 0) {
            clampedAspect(vf.width, vf.height)
        } else {
            Rational(16, 9)
        }

        return PictureInPictureParams.Builder()
            .setActions(listOf(accion))
            .setAspectRatio(aspecto)
            .build()
    }

    /**
     * Se llama justo antes de que la app deje de estar en primer plano por una
     * acción del usuario (Inicio, recientes, abrir otra app) — es el momento
     * correcto para pasar a PiP, a diferencia de onStop, que llega demasiado tarde.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying != true) return
        val params = buildPipParams() ?: return
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // En la ventanita no se puede tocar nada más que el video: se ocultan
            // los controles propios para no dejar botones "muertos" en pantalla.
            binding.playerView.setUseController(false)
            binding.playerView.hideController()
            binding.topBar.visibility = View.GONE
            binding.btnUnlock.visibility = View.GONE
            hideNextBar()
        } else if (!locked) {
            binding.playerView.setUseController(true)
            binding.playerView.showController()
            binding.topBar.visibility = View.VISIBLE
        }
    }

    /** Sin este permiso, en Android 13+ la notificación del servicio no se ve. */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!PlayerPrefs.getBackground(this)) return
        val concedido = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!concedido) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 91
            )
        }
    }

    /** Con la pantalla bloqueada, el botón físico de atrás tampoco sale. */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (locked) {
            Toast.makeText(this, R.string.player_unlock, Toast.LENGTH_SHORT).show()
            return
        }
        super.onBackPressed()
    }

    // ---------------- Datos de entrada ----------------

    private fun readPlaylist() {
        playlistUrls = intent.getStringArrayListExtra(EXTRA_PLAYLIST_URLS).orEmpty()
        playlistTitles = intent.getStringArrayListExtra(EXTRA_PLAYLIST_TITLES).orEmpty()
        playlistIndex = intent.getIntExtra(EXTRA_PLAYLIST_INDEX, 0)
    }

    private fun readFavoriteItem() {
        val id = intent.getIntExtra(EXTRA_ITEM_ID, -1)
        if (id < 0) return
        val type = runCatching {
            ContentType.valueOf(intent.getStringExtra(EXTRA_ITEM_TYPE) ?: ContentType.LIVE.name)
        }.getOrDefault(ContentType.LIVE)

        favoriteItem = ContentItem(
            id = id,
            name = contentTitle,
            icon = intent.getStringExtra(EXTRA_ITEM_ICON),
            categoryId = intent.getStringExtra(EXTRA_ITEM_CATEGORY),
            type = type,
            containerExtension = intent.getStringExtra(EXTRA_ITEM_EXT)
        )
    }

    // ---------------- Controles ----------------

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAudio.setOnClickListener { showTrackDialog(C.TRACK_TYPE_AUDIO) }
        binding.btnSubtitles.setOnClickListener { showTrackDialog(C.TRACK_TYPE_TEXT) }
        binding.btnQuality.setOnClickListener { showTrackDialog(C.TRACK_TYPE_VIDEO) }
        binding.btnAspect.setOnClickListener { showAspectDialog() }
        binding.btnBuffer.setOnClickListener { showBufferDialog() }
        binding.btnLock.setOnClickListener { setLocked(true) }
        binding.btnNext.setOnClickListener { playNextEpisode() }

        binding.btnFavorite.setOnClickListener { toggleFavorite() }
        binding.btnFavorite.visibility = if (favoriteItem == null) View.GONE else View.VISIBLE
        refreshFavoriteIcon()

        // Solo tiene sentido si hay más episodios por delante
        binding.btnNext.visibility =
            if (playlistIndex + 1 < playlistUrls.size) View.VISIBLE else View.GONE

        binding.btnPlayNextNow.background = Appearance.gradient(this, 10f)
        binding.btnPlayNextNow.setOnClickListener {
            hideNextBar()
            playNextEpisode()
        }
        binding.btnCancelNext.setOnClickListener { hideNextBar() }

        // La barra propia aparece y desaparece junto con los controles nativos
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (!locked) binding.topBar.visibility = visibility
            }
        )

        // Doble toque en el candado para desbloquear (evita desbloqueos accidentales)
        binding.btnUnlock.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastUnlockTap < 700L) {
                setLocked(false)
            } else {
                lastUnlockTap = now
                Toast.makeText(this, R.string.player_unlock, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------- Bloqueo de pantalla ----------------

    private fun setLocked(value: Boolean) {
        locked = value
        if (value) {
            binding.playerView.setUseController(false)
            binding.playerView.hideController()
            binding.topBar.visibility = View.GONE
            binding.btnUnlock.visibility = View.VISIBLE
            hideNextBar()
            Toast.makeText(this, R.string.player_locked, Toast.LENGTH_SHORT).show()
        } else {
            binding.playerView.setUseController(true)
            binding.btnUnlock.visibility = View.GONE
            binding.topBar.visibility = View.VISIBLE
            binding.playerView.showController()
            Toast.makeText(this, R.string.player_unlocked, Toast.LENGTH_SHORT).show()
        }
    }

    /** Con la pantalla bloqueada se descarta cualquier toque salvo el del candado. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (locked) {
            val unlock = binding.btnUnlock
            val pos = IntArray(2)
            unlock.getLocationOnScreen(pos)
            val dentro = ev.rawX >= pos[0] && ev.rawX <= pos[0] + unlock.width &&
                ev.rawY >= pos[1] && ev.rawY <= pos[1] + unlock.height
            if (!dentro) return true   // se traga el toque
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------------- Favoritos ----------------

    private fun toggleFavorite() {
        val item = favoriteItem ?: return
        val added = Favorites.toggle(this, item)
        refreshFavoriteIcon()
        Toast.makeText(
            this,
            if (added) R.string.fav_added else R.string.fav_removed,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun refreshFavoriteIcon() {
        val item = favoriteItem ?: return
        val fav = Favorites.isFavorite(this, item)
        binding.btnFavorite.setImageResource(
            if (fav) android.R.drawable.star_big_on else android.R.drawable.star_big_off
        )
        binding.btnFavorite.setColorFilter(
            ContextCompat.getColor(this, if (fav) R.color.star_active else R.color.text_light)
        )
    }

    // ---------------- Pistas: audio, subtítulos y resolución ----------------

    /**
     * Lista las pistas disponibles del tipo pedido y deja elegir una.
     * "Automática" devuelve el control al selector; en subtítulos se agrega
     * además la opción de desactivarlos.
     */
    private fun showTrackDialog(trackType: Int) {
        val exo = player ?: return
        val grupos = exo.currentTracks.groups.filter { it.type == trackType && it.isSupported }

        if (grupos.isEmpty()) {
            val msg = if (trackType == C.TRACK_TYPE_TEXT) R.string.player_no_subtitles
            else R.string.player_no_tracks
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }

        data class Opcion(val etiqueta: String, val grupo: Tracks.Group?, val indice: Int)

        val opciones = mutableListOf<Opcion>()
        opciones.add(Opcion(getString(R.string.track_auto), null, -1))
        if (trackType == C.TRACK_TYPE_TEXT) {
            opciones.add(Opcion(getString(R.string.track_disabled), null, -2))
        }

        grupos.forEach { grupo ->
            for (i in 0 until grupo.length) {
                if (!grupo.isTrackSupported(i)) continue
                opciones.add(Opcion(describeTrack(trackType, grupo, i), grupo, i))
            }
        }

        val seleccionado = opciones.indexOfFirst {
            it.grupo != null && it.indice >= 0 && it.grupo.isTrackSelected(it.indice)
        }.takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle(titleFor(trackType))
            .setSingleChoiceItems(
                opciones.map { it.etiqueta }.toTypedArray(),
                seleccionado
            ) { dialog, which ->
                val opcion = opciones[which]
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(trackType)
                    .setTrackTypeDisabled(trackType, opcion.indice == -2)
                    .apply {
                        val grupo = opcion.grupo
                        if (grupo != null && opcion.indice >= 0) {
                            addOverride(
                                TrackSelectionOverride(grupo.mediaTrackGroup, opcion.indice)
                            )
                        }
                    }
                    .build()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun titleFor(trackType: Int) = when (trackType) {
        C.TRACK_TYPE_AUDIO -> R.string.player_audio
        C.TRACK_TYPE_TEXT -> R.string.player_subtitles
        else -> R.string.player_quality
    }

    /** Etiqueta legible de una pista: idioma, resolución, códec o canales. */
    private fun describeTrack(trackType: Int, grupo: Tracks.Group, i: Int): String {
        val f = grupo.getTrackFormat(i)
        return when (trackType) {
            C.TRACK_TYPE_VIDEO -> buildString {
                if (f.height > 0) append("${f.height}p") else append("Pista ${i + 1}")
                if (f.bitrate > 0) append(" · ${f.bitrate / 1000} kbps")
                f.codecs?.substringBefore('.')?.let { append(" · $it") }
            }
            C.TRACK_TYPE_AUDIO -> buildString {
                append(f.language?.uppercase() ?: f.label ?: "Pista ${i + 1}")
                if (f.channelCount > 0) append(" · ${f.channelCount}ch")
                f.sampleMimeType?.substringAfter('/')?.let { append(" · ${it.uppercase()}") }
            }
            else -> f.label ?: f.language?.uppercase() ?: "Subtítulo ${i + 1}"
        }
    }

    // ---------------- Aspecto y buffer en caliente ----------------

    private fun applyAspect() {
        binding.playerView.resizeMode = when (PlayerPrefs.getAspect(this)) {
            PlayerPrefs.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            PlayerPrefs.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun showAspectDialog() {
        val opciones = arrayOf(
            PlayerPrefs.aspectLabel(this, PlayerPrefs.FIT),
            PlayerPrefs.aspectLabel(this, PlayerPrefs.CROP),
            PlayerPrefs.aspectLabel(this, PlayerPrefs.STRETCH)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_aspect)
            .setSingleChoiceItems(opciones, PlayerPrefs.getAspect(this)) { dialog, which ->
                PlayerPrefs.setAspect(this, which)
                applyAspect()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * El buffer se fija al construir el reproductor, así que cambiarlo obliga a
     * reconstruirlo. Se guarda la posición para retomar donde iba.
     */
    private fun showBufferDialog() {
        val opciones = arrayOf(
            PlayerPrefs.bufferLabel(this, PlayerPrefs.BUFFER_LOW),
            PlayerPrefs.bufferLabel(this, PlayerPrefs.BUFFER_NORMAL),
            PlayerPrefs.bufferLabel(this, PlayerPrefs.BUFFER_HIGH)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.setting_buffer)
            .setSingleChoiceItems(opciones, PlayerPrefs.getBuffer(this)) { dialog, which ->
                PlayerPrefs.setBuffer(this, which)
                val posicion = player?.currentPosition ?: 0L
                PlaybackHolder.release()
                startPlayback(streamUrl, resumeAtMs = posicion)
                Toast.makeText(
                    this,
                    getString(R.string.buffer_applied, PlayerPrefs.bufferLabel(this, which)),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- Siguiente episodio ----------------

    private fun hasNextEpisode() = playlistIndex + 1 < playlistUrls.size

    private fun showNextBar() {
        if (!hasNextEpisode()) return
        countdown = NEXT_COUNTDOWN_SECONDS
        binding.tvNextCountdown.text = getString(R.string.player_next_in, countdown)
        binding.nextEpisodeBar.visibility = View.VISIBLE
        ui.postDelayed(countdownTick, 1000L)
    }

    private fun hideNextBar() {
        ui.removeCallbacks(countdownTick)
        binding.nextEpisodeBar.visibility = View.GONE
    }

    private fun playNextEpisode() {
        if (!hasNextEpisode()) return
        playlistIndex++
        streamUrl = playlistUrls[playlistIndex]
        contentTitle = playlistTitles.getOrElse(playlistIndex) { contentTitle }
        binding.tvNowPlaying.text = contentTitle
        binding.btnNext.visibility = if (hasNextEpisode()) View.VISIBLE else View.GONE
        retries = 0
        audioAvisado = false
        PlaybackHolder.release()
        startPlayback(streamUrl, resumeAtMs = 0L)
    }

    // ---------------- Reproducción ----------------

    private fun startPlayback(url: String, resumeAtMs: Long) {
        val (minBuffer, maxBuffer) = PlayerPrefs.bufferMillis(PlayerPrefs.getBuffer(this))
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBuffer,
                maxBuffer,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()

        player = PlayerFactory.build(this, loadControl).also { exo ->
            binding.playerView.player = exo
            PlaybackHolder.attach(exo, url, contentTitle)
            exo.setMediaItem(MediaItem.fromUri(url))
            exo.prepare()
            if (resumeAtMs > 0) exo.seekTo(resumeAtMs)
            exo.playWhenReady = true

            exo.addListener(object : Player.Listener {

                /**
                 * Causa habitual de "se ve pero no se oye": el stream trae audio
                 * AC-3/E-AC-3/DTS y el aparato no tiene decodificador para eso.
                 * Acá se detecta y, si hay otra pista que sí se pueda decodificar
                 * (por ejemplo una AAC), se cambia sola.
                 */
                override fun onTracksChanged(tracks: Tracks) {
                    if (audioAvisado) return
                    val grupos = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    if (grupos.isEmpty()) return   // todavía no llegaron las pistas

                    val yaSuena = grupos.any { g ->
                        (0 until g.length).any { g.isTrackSelected(it) && g.isTrackSupported(it) }
                    }
                    if (yaSuena) return

                    // Buscar cualquier pista de audio que el aparato sí soporte
                    grupos.forEach { g ->
                        for (i in 0 until g.length) {
                            if (g.isTrackSupported(i)) {
                                exo.trackSelectionParameters = exo.trackSelectionParameters
                                    .buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                    .addOverride(TrackSelectionOverride(g.mediaTrackGroup, i))
                                    .build()
                                return
                            }
                        }
                    }

                    // Ninguna es decodificable: se avisa con el códec concreto
                    audioAvisado = true
                    val codec = grupos.firstOrNull()
                        ?.getTrackFormat(0)?.sampleMimeType
                        ?.substringAfter('/')?.uppercase()
                        ?: "desconocido"
                    Toast.makeText(
                        this@PlayerActivity,
                        getString(R.string.audio_unsupported, codec),
                        Toast.LENGTH_LONG
                    ).show()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    binding.progressBar.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                    if (state == Player.STATE_READY) retries = 0

                    // Fin del episodio: encadenar con el siguiente
                    if (state == Player.STATE_ENDED &&
                        PlayerPrefs.getAutoPlayNext(this@PlayerActivity) && hasNextEpisode()
                    ) {
                        showNextBar()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (PlayerPrefs.getAutoReconnect(this@PlayerActivity) && retries < MAX_RETRIES) {
                        retries++
                        binding.progressBar.visibility = View.VISIBLE
                        exo.stop()
                        exo.setMediaItem(MediaItem.fromUri(url))
                        exo.prepare()
                        exo.playWhenReady = true
                    } else {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(
                            this@PlayerActivity,
                            getString(R.string.player_error, error.errorCodeName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            })
        }
    }
}
