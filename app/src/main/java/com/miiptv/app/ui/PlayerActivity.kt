package com.miiptv.app.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
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
import com.miiptv.app.api.Session
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.Epg
import com.miiptv.app.util.RemoteControl
import com.miiptv.app.util.History
import com.miiptv.app.util.Favorites
import com.miiptv.app.util.PlayerFactory
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.util.PlaybackHolder
import com.miiptv.app.util.PlayerPrefs
import com.miiptv.app.service.PlaybackService
import com.squareup.picasso.Picasso
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
        /** El EPG solo se muestra si viene en true: lo pone MainActivity cuando se entra desde Canales o PPV. */
        const val EXTRA_EPG_ALLOWED = "extra_epg_allowed"
        const val EXTRA_ITEM_ICON = "extra_item_icon"
        const val EXTRA_ITEM_CATEGORY = "extra_item_category"
        const val EXTRA_ITEM_TYPE = "extra_item_type"
        const val EXTRA_ITEM_EXT = "extra_item_ext"

        // Lista de episodios, para encadenar la reproducción automática
        const val EXTRA_PLAYLIST_URLS = "extra_playlist_urls"
        const val EXTRA_PLAYLIST_TITLES = "extra_playlist_titles"
        const val EXTRA_PLAYLIST_INDEX = "extra_playlist_index"

        /**
         * Modo radio: lo que suena es una emisora, no un video. Cambia toda la
         * pantalla (fondo propio, logo grande y barra de reproducción) y la
         * misma lista de arriba pasa a ser la lista de emisoras por las que se
         * puede saltar con los botones anterior/siguiente.
         */
        const val EXTRA_IS_RADIO = "extra_is_radio"
        const val EXTRA_PLAYLIST_ICONS = "extra_playlist_icons"
        const val EXTRA_PLAYLIST_IDS = "extra_playlist_ids"
        /** De dónde salió la lista: "🇪🇸  España", "🎧  Loca FM", etc. */
        const val EXTRA_RADIO_SOURCE = "extra_radio_source"

        private const val MAX_RETRIES = 5
        private const val NEXT_COUNTDOWN_SECONDS = 8
        private const val ACTION_PIP_PLAY_PAUSE = "com.miiptv.app.PIP_PLAY_PAUSE"
    }

    private lateinit var binding: ActivityPlayerBinding

    /**
     * Botón de siguiente episodio. Vive dentro de custom_player_control_view,
     * que lo infla Media3 y no ViewBinding, así que hay que buscarlo a mano
     * sobre el PlayerView una vez creado.
     */
    private var btnNextEpisode: ImageButton? = null
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

    /** En modo radio, la misma lista trae además el logo y el id de cada emisora. */
    private var playlistIcons: List<String> = emptyList()
    private var playlistIds: List<Int> = emptyList()
    private var isRadio = false

    /** Tipo del contenido que se está viendo. Decide si hay zapping de canales. */
    private var itemType: ContentType = ContentType.LIVE
    /** true solo si se entró desde Canales o PPV (lo decide MainActivity). */
    private var epgAllowed = false
    private var radioSourceLabel: String? = null
    /** Animaciones del ecualizador; se cancelan al pausar y al salir. */
    private val eqAnimators = mutableListOf<ObjectAnimator>()

    private var playlistIndex = 0

    /**
     * Zapping de canales en vivo. Reutiliza la misma lista que las emisoras de
     * radio, pero solo con id y nombre: la URL de un canal es determinista
     * (Session.liveStreamUrl), así que mandarla por el intent sería repetir
     * datos y acercarse al límite de tamaño de una transacción Binder.
     */
    private val hasChannelList: Boolean
        get() = !isRadio && itemType == ContentType.LIVE && playlistIds.size > 1

    /** Mantiene el ecualizador y el botón de la barra al día con el audio. */
    private val radioUiListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateRadioPlaybackState(isPlaying)
        }

        /**
         * Una emisora tarda un momento en enganchar. Decirlo en la barra evita
         * que parezca que el botón no hizo nada.
         */
        override fun onPlaybackStateChanged(state: Int) {
            if (!isRadio) return
            binding.tvBarSub.text = if (state == Player.STATE_BUFFERING) {
                getString(R.string.radio_connecting)
            } else {
                stationPositionText()
            }
        }
    }

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
        isRadio = intent.getBooleanExtra(EXTRA_IS_RADIO, false)
        radioSourceLabel = intent.getStringExtra(EXTRA_RADIO_SOURCE)?.takeIf { it.isNotBlank() }

        // En móvil el resto de la app queda bloqueada en vertical (ver DeviceMode);
        // el reproductor es la única pantalla que pasa a horizontal, y lo hace solo
        // apenas se reproduce contenido. Al salir de acá, la pantalla anterior ya
        // está fijada en vertical y el sistema vuelve a esa orientación solo.
        // SENSOR_LANDSCAPE deja girar entre horizontal-izquierda/derecha según se
        // gire el celular, pero nunca cae en vertical.
        //
        // Con una radio no hay imagen que mirar: forzar horizontal no aporta nada
        // y el celular se sostiene mejor en vertical, así que ahí se respeta la
        // orientación que tenga puesta el usuario.
        requestedOrientation = when {
            isRadio && DeviceMode.isMobile(this) -> ActivityInfo.SCREEN_ORIENTATION_USER
            DeviceMode.isMobile(this) -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        readPlaylist()
        readFavoriteItem()

        binding.tvNowPlaying.text = contentTitle
        binding.tvNowPlaying.isSelected = true   // activa el desplazamiento del texto largo
        epgAllowed = itemType == ContentType.LIVE && intent.getBooleanExtra(EXTRA_EPG_ALLOWED, false)
        if (epgAllowed && intent.hasExtra(EXTRA_ITEM_ID)) {
            refreshEpgNow(intent.getIntExtra(EXTRA_ITEM_ID, 0))
        }

        if (PlayerPrefs.getKeepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        applyAspect()

        binding.playerView.subtitleView?.setFixedTextSize(
            TypedValue.COMPLEX_UNIT_SP, Appearance.getSubtitleSize(this).toFloat()
        )

        setupControls()
        setupRadioMode()
        setupTvFocus()
        askNotificationPermission()
        registerPipReceiver()

        if (PlaybackHolder.canResume(streamUrl)) {
            // Vuelve desde la notificación: se engancha al reproductor que ya está sonando
            PlaybackService.stop(this)
            player = PlaybackHolder.player
            binding.playerView.player = player
            if (isRadio) {
                player?.addListener(radioUiListener)
                updateRadioPlaybackState(player?.isPlaying == true)
            }
        } else {
            PlaybackHolder.release()
            startPlayback(streamUrl, resumeAtMs = 0L)
        }
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(countdownTick)
        // Sin pantalla visible las animaciones solo gastan batería
        stopEqualizer()

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
                if (isRadio) {
                    vivo.addListener(radioUiListener)
                    updateRadioPlaybackState(vivo.isPlaying)
                }
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
        stopEqualizer()
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
            !isRadio &&   // una emisora no tiene imagen: la ventanita saldría vacía
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
        playlistIcons = intent.getStringArrayListExtra(EXTRA_PLAYLIST_ICONS).orEmpty()
        playlistIds = intent.getIntegerArrayListExtra(EXTRA_PLAYLIST_IDS).orEmpty()
        playlistIndex = intent.getIntExtra(EXTRA_PLAYLIST_INDEX, 0)
        itemType = runCatching {
            ContentType.valueOf(intent.getStringExtra(EXTRA_ITEM_TYPE) ?: ContentType.LIVE.name)
        }.getOrDefault(ContentType.LIVE)

        // El índice tiene que caer dentro de la lista sí o sí. Los canales en
        // vivo viajan sin URLs (se arman solas), así que se valida contra la
        // lista que venga con datos.
        val largo = maxOf(playlistUrls.size, playlistIds.size)
        if (playlistIndex !in 0 until largo) playlistIndex = 0
    }

    /**
     * Arma el ítem con el que trabajan los botones de favorito.
     *
     * ---------------------------------------------------------------------
     * OJO CON LA COMPROBACIÓN DE ABAJO
     *
     * Antes decía `if (id < 0) return`, usando el -1 por defecto de
     * getIntExtra como señal de "no vino el dato". El problema: los ids de las
     * emisoras de radio salen de String.hashCode() (ver RadioCatalog), y un
     * hashCode es NEGATIVO más o menos la mitad de las veces.
     *
     * O sea que en una emisora de cada dos, un id perfectamente válido se
     * confundía con "falta el dato", favoriteItem quedaba en null y los dos
     * botones de favorito desaparecían. Y como al cambiar de emisora se hace
     * `favoriteItem?.copy(...)`, un null seguía siendo null: no se recuperaba
     * ni pasando a la siguiente.
     *
     * hasExtra() responde exactamente lo que hay que preguntar —si el dato vino
     * o no— sin reservarse ningún valor. Los ids guardados no cambian, así que
     * los favoritos que ya existan se siguen reconociendo.
     * ---------------------------------------------------------------------
     */
    private fun readFavoriteItem() {
        if (!intent.hasExtra(EXTRA_ITEM_ID)) return
        val id = intent.getIntExtra(EXTRA_ITEM_ID, 0)
        val type = itemType

        favoriteItem = ContentItem(
            id = id,
            name = contentTitle,
            icon = intent.getStringExtra(EXTRA_ITEM_ICON),
            categoryId = intent.getStringExtra(EXTRA_ITEM_CATEGORY),
            type = type,
            containerExtension = intent.getStringExtra(EXTRA_ITEM_EXT),
            // Una emisora se guarda con su dirección: es lo único que la
            // distingue de un canal del servidor y lo que permite volver a
            // ponerla desde Favoritos.
            streamUrl = if (isRadio) streamUrl else null
        )
    }

    /**
     * Programa que está al aire ahora en el canal [streamId], mostrado bajo el
     * nombre del canal en la barra superior. Solo eso: sin horarios ni
     * programación futura. Se oculta si el panel no tiene EPG para ese canal.
     */
    private fun refreshEpgNow(streamId: Int) {
        binding.tvEpgNow.visibility = View.GONE
        binding.tvEpgNow.tag = streamId
        Epg.nowPlaying(this, streamId) { titulo ->
            if (isFinishing || isDestroyed) return@nowPlaying
            if (binding.tvEpgNow.tag == streamId) {
                binding.tvEpgNow.text = titulo
                binding.tvEpgNow.visibility = if (titulo.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        }
    }

    // ---------------- Controles ----------------

    private fun setupControls() {
        Appearance.applyLevel(binding.btnBack, Appearance.Level.PRIMARY, 22f)
        binding.btnBack.compoundDrawablesRelative.forEach {
            it?.mutate()?.setTint(binding.btnBack.currentTextColor)
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAudio.setOnClickListener { showTrackDialog(C.TRACK_TYPE_AUDIO) }
        binding.btnSubtitles.setOnClickListener { showTrackDialog(C.TRACK_TYPE_TEXT) }
        binding.btnQuality.setOnClickListener { showTrackDialog(C.TRACK_TYPE_VIDEO) }
        binding.btnAspect.setOnClickListener { showAspectDialog() }
        binding.btnBuffer.setOnClickListener { showBufferDialog() }
        binding.btnLock.setOnClickListener { setLocked(true) }
        btnNextEpisode = binding.playerView.findViewById(R.id.btnNextEpisode)
        btnNextEpisode?.setOnClickListener { playNextEpisode() }

        binding.btnFavorite.setOnClickListener { toggleFavorite() }
        binding.btnFavorite.visibility = if (favoriteItem == null) View.GONE else View.VISIBLE
        refreshFavoriteIcon()

        // Solo tiene sentido si hay más episodios por delante
        btnNextEpisode?.visibility = if (hasNextEpisode()) View.VISIBLE else View.GONE

        binding.btnPlayNextNow.background = Appearance.gradient(this, 10f)
        binding.btnPlayNextNow.setOnClickListener {
            hideNextBar()
            playNextEpisode()
        }
        binding.btnCancelNext.setOnClickListener { hideNextBar() }

        binding.btnChannelPrev.setOnClickListener { changeChannel(-1) }
        binding.btnChannelNext.setOnClickListener { changeChannel(+1) }
        updateChannelNav()

        // La barra propia aparece y desaparece junto con los controles nativos
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                if (!locked) {
                    binding.topBar.visibility = visibility
                    binding.channelNav.visibility =
                        if (hasChannelList) visibility else View.GONE
                }
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
            binding.channelNav.visibility = View.GONE
            binding.radioBar.visibility = View.GONE
            // Con la pantalla bloqueada queda 100% limpia: ni el candado se ve.
            // Solo aparece un momento al tocar la pantalla (ver dispatchTouchEvent).
            binding.btnUnlock.visibility = View.GONE
            hideNextBar()
            Toast.makeText(this, R.string.player_locked, Toast.LENGTH_SHORT).show()
        } else {
            lockIconHide?.let { ui.removeCallbacks(it) }
            binding.playerView.setUseController(true)
            binding.btnUnlock.visibility = View.GONE
            binding.topBar.visibility = View.VISIBLE
            if (hasChannelList) binding.channelNav.visibility = View.VISIBLE
            if (isRadio) binding.radioBar.visibility = View.VISIBLE
            binding.playerView.showController()
            Toast.makeText(this, R.string.player_unlocked, Toast.LENGTH_SHORT).show()
        }
    }

    private var lockIconHide: Runnable? = null

    /** Muestra el candado unos segundos y lo vuelve a esconder si no se lo toca de nuevo. */
    private fun showLockIconTemporarily() {
        binding.btnUnlock.visibility = View.VISIBLE
        lockIconHide?.let { ui.removeCallbacks(it) }
        val runnable = Runnable { if (locked) binding.btnUnlock.visibility = View.GONE }
        lockIconHide = runnable
        ui.postDelayed(runnable, 3000L)
    }

    /** Con la pantalla bloqueada se descarta cualquier toque salvo el del candado. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (locked) {
            val unlock = binding.btnUnlock
            if (unlock.visibility != View.VISIBLE) {
                // Primer toque en pantalla: solo revela el candado, no desbloquea nada.
                if (ev.action == MotionEvent.ACTION_DOWN) showLockIconTemporarily()
                return true
            }
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

    /**
     * Deja los dos botones de favorito diciendo lo mismo.
     *
     * Son dos: la estrella de la barra superior y, en radio, el botón con
     * etiqueta que está al lado de Inicio. Se pueden tocar los dos, así que si
     * uno no se repintara quedarían contradiciéndose en pantalla.
     */
    private fun refreshFavoriteIcon() {
        val item = favoriteItem ?: return
        val fav = Favorites.isFavorite(this, item)

        binding.btnFavorite.setImageResource(
            if (fav) android.R.drawable.star_big_on else android.R.drawable.star_big_off
        )
        binding.btnFavorite.setColorFilter(
            ContextCompat.getColor(this, if (fav) R.color.star_active else R.color.text_light)
        )

        if (!isRadio) return
        // Guardada: se rellena con el color de acento. Sin guardar: apagada.
        // El texto también cambia, porque un corazón encendido puede leerse
        // igual como "está guardada" que como "tocá acá para guardarla".
        Appearance.applyLevel(
            binding.btnRadioFavorite,
            if (fav) Appearance.Level.PRIMARY else Appearance.Level.INACTIVE,
            22f
        )
        binding.btnRadioFavorite.setText(
            if (fav) R.string.radio_fav_saved else R.string.radio_fav_add
        )
        binding.btnRadioFavorite.compoundDrawablesRelative.forEach {
            it?.mutate()?.setTint(binding.btnRadioFavorite.currentTextColor)
        }
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

    // ---------------- Modo radio ----------------

    /**
     * Una emisora no manda imagen, así que la superficie del reproductor solo
     * aportaría el rectángulo negro que se veía antes. En modo radio se saca de
     * en medio y en su lugar queda el fondo de la sección, con el logo de la
     * emisora, su nombre y un ecualizador que se mueve mientras hay audio.
     */
    /**
     * Resalte fuerte del botón enfocado, solo con control remoto.
     *
     * Los botones del reproductor son el peor caso de toda la app: flotan sobre
     * el video, que puede ser de cualquier color y estar en movimiento, y hasta
     * ahora su único fondo era `selectableItemBackgroundBorderless`, un
     * destello pensado para el dedo que en un televisor no se ve. Con el mando
     * no había forma de saber sobre cuál estabas parado.
     *
     * Por eso acá el resalte es más rotundo que en las listas: relleno OPACO del
     * color de acento, anillo blanco de 3dp y la vista un 18% más grande. Se ve
     * lo mismo sobre una escena negra que sobre una nevada.
     */
    private fun setupTvFocus() {
        val remoto = RemoteControl.isEnabled(this)
        if (!remoto) return

        // Redondos: los iconos de la barra superior, el zapping y la radio
        listOf(
            binding.btnFavorite, binding.btnAudio, binding.btnSubtitles,
            binding.btnQuality, binding.btnAspect, binding.btnBuffer, binding.btnLock,
            binding.btnChannelPrev, binding.btnChannelNext,
            binding.btnPrevStation, binding.btnRadioPlayPause, binding.btnNextStation
        ).forEach { RemoteControl.applyIconFocus(it, true) }

        // Rectangulares
        RemoteControl.applyIconFocus(binding.btnUnlock, true, circular = false)
        RemoteControl.applyIconFocus(binding.btnPlayNextNow, true, circular = false, cornerRadiusDp = 18f)
        RemoteControl.applyIconFocus(binding.btnCancelNext, true, circular = false, cornerRadiusDp = 18f)

        // Retroceder / reproducir / adelantar: los infla Media3 dentro del
        // PlayerView, así que se recorre el árbol en vez de depender de los
        // identificadores internos de la librería.
        RemoteControl.applyIconFocusToTree(binding.playerView, true)

        // btnBack, btnRadioHome y btnRadioFavorite no entran acá: llevan estilo
        // NavItem y ya reciben su color de foco desde Appearance.applyLevel.
    }

    private fun setupRadioMode() {
        if (!isRadio) return

        binding.playerView.visibility = View.GONE
        binding.radioBackdrop.visibility = View.VISIBLE
        binding.radioBar.visibility = View.VISIBLE

        // Sin video no hay calidad, subtítulos ni relación de aspecto que elegir
        binding.btnQuality.visibility = View.GONE
        binding.btnSubtitles.visibility = View.GONE
        binding.btnAspect.visibility = View.GONE

        val acento = Appearance.accent(this)

        // Aro del logo grande, en el color de acento elegido en Personalizar
        binding.radioLogoHolder.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xE60E0820.toInt())
            setStroke(dp(2), acento)
        }
        binding.btnRadioPlayPause.background = Appearance.gradientOval(this)
        for (i in 0 until binding.equalizer.childCount) {
            binding.equalizer.getChildAt(i).background?.mutate()?.setTint(acento)
        }

        // Botón de inicio, con el mismo tratamiento que el del menú principal
        Appearance.applyLevel(binding.btnRadioHome, Appearance.Level.PRIMARY, 22f)
        binding.btnRadioHome.compoundDrawablesRelative.forEach {
            it?.mutate()?.setTint(binding.btnRadioHome.currentTextColor)
        }
        binding.btnRadioHome.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }

        // Favorito con etiqueta, al lado de Inicio. Hace lo mismo que la
        // estrella de la barra de arriba, que en una tele es diminuta y queda
        // lejos de la mano.
        binding.btnRadioFavorite.visibility =
            if (favoriteItem == null) View.GONE else View.VISIBLE
        binding.btnRadioFavorite.setOnClickListener { toggleFavorite() }
        // Deja el botón con el estado correcto de entrada (guardada o no)
        refreshFavoriteIcon()

        binding.btnPrevStation.setOnClickListener { changeStation(-1) }
        binding.btnNextStation.setOnClickListener { changeStation(+1) }
        binding.btnRadioPlayPause.setOnClickListener {
            val exo = player ?: return@setOnClickListener
            if (exo.isPlaying) exo.pause() else exo.play()
        }

        renderRadioNowPlaying()
    }

    /** Nombre, logo y posición dentro de la lista de la emisora que suena. */
    private fun renderRadioNowPlaying() {
        if (!isRadio) return

        binding.tvNowPlaying.text = contentTitle
        binding.tvRadioName.text = contentTitle
        binding.tvBarName.text = contentTitle
        binding.tvBarName.isSelected = true

        val total = playlistUrls.size
        val posicion = stationPositionText()
        binding.tvBarSub.text = posicion
        binding.tvRadioMeta.text = radioSourceLabel?.let {
            if (total > 1) "$it · $posicion" else it
        } ?: posicion

        loadRadioLogo(
            playlistIcons.getOrNull(playlistIndex)?.takeIf { it.isNotBlank() }
                ?: intent.getStringExtra(EXTRA_ITEM_ICON)?.takeIf { it.isNotBlank() }
        )

        setStationButton(binding.btnPrevStation, playlistIndex > 0)
        setStationButton(binding.btnNextStation, playlistIndex + 1 < total)
    }

    /** "Emisora 3 de 42", o "Emisora única" si la lista trae una sola. */
    private fun stationPositionText(): String {
        val total = playlistUrls.size
        return if (total > 1) {
            getString(R.string.radio_position, playlistIndex + 1, total)
        } else {
            getString(R.string.radio_only_station)
        }
    }

    /** Un botón de salto apagado se ve apagado: no da un toque en falso. */
    private fun setStationButton(button: ImageView, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.28f
    }

    private fun loadRadioLogo(url: String?) {
        val vistas = listOf(binding.ivRadioLogo, binding.ivBarLogo)
        if (url.isNullOrBlank()) {
            vistas.forEach { it.setImageResource(R.drawable.ic_radio) }
            return
        }
        vistas.forEach { vista ->
            Picasso.get()
                .load(url)
                .placeholder(R.drawable.ic_radio)
                .error(R.drawable.ic_radio)
                .into(vista)
        }
    }

    /** Salta a la emisora anterior (-1) o la siguiente (+1) de la misma lista. */
    /**
     * Salta al canal anterior o siguiente de la categoría desde la que se entró.
     *
     * La URL no viaja en el intent: se arma acá con el id, igual que al abrir el
     * canal desde la lista. Así la lista puede ser larga sin acercarse al límite
     * de tamaño de una transacción Binder.
     */
    private fun changeChannel(delta: Int) {
        if (!hasChannelList) return
        val destino = playlistIndex + delta
        if (destino !in playlistIds.indices) {
            Toast.makeText(this, R.string.player_no_more_channels, Toast.LENGTH_SHORT).show()
            return
        }

        playlistIndex = destino
        val id = playlistIds[destino]
        streamUrl = Session.liveStreamUrl(this, id)
        contentTitle = playlistTitles.getOrElse(destino) { contentTitle }
        retries = 0
        audioAvisado = false

        binding.tvNowPlaying.text = contentTitle
        if (epgAllowed) refreshEpgNow(id)
        // La estrella tiene que marcar el canal que se ve ahora, no el anterior
        favoriteItem = favoriteItem?.copy(id = id, name = contentTitle, streamUrl = null)
        refreshFavoriteIcon()
        favoriteItem?.let { History.add(this, it) }
        updateChannelNav()

        PlaybackHolder.release()
        startPlayback(streamUrl, resumeAtMs = 0L)
    }

    /** Muestra los botones solo si hay lista, y apaga el que no lleva a ningún lado. */
    private fun updateChannelNav() {
        if (!hasChannelList) {
            binding.channelNav.visibility = View.GONE
            return
        }
        binding.channelNav.visibility = if (locked) View.GONE else View.VISIBLE
        setStationButton(binding.btnChannelPrev, playlistIndex > 0)
        setStationButton(binding.btnChannelNext, playlistIndex + 1 < playlistIds.size)
    }

    private fun changeStation(delta: Int) {
        if (!isRadio) return
        val destino = playlistIndex + delta
        if (destino !in playlistUrls.indices) {
            Toast.makeText(this, R.string.radio_no_more, Toast.LENGTH_SHORT).show()
            return
        }

        playlistIndex = destino
        streamUrl = playlistUrls[destino]
        contentTitle = playlistTitles.getOrElse(destino) { contentTitle }
        retries = 0
        audioAvisado = false

        // La estrella tiene que marcar la emisora que suena ahora, no la anterior
        favoriteItem = favoriteItem?.copy(
            id = playlistIds.getOrElse(destino) { streamUrl.hashCode() },
            name = contentTitle,
            icon = playlistIcons.getOrNull(destino)?.takeIf { it.isNotBlank() },
            streamUrl = streamUrl
        )
        refreshFavoriteIcon()
        renderRadioNowPlaying()

        PlaybackHolder.release()
        startPlayback(streamUrl, resumeAtMs = 0L)
    }

    /** Botón de la barra y ecualizador, en sintonía con lo que hace el audio. */
    private fun updateRadioPlaybackState(playing: Boolean) {
        if (!isRadio) return
        binding.btnRadioPlayPause.setImageResource(
            if (playing) R.drawable.ic_radio_pause else R.drawable.ic_radio_play
        )
        if (playing) startEqualizer() else stopEqualizer()
    }

    /**
     * Las barras suben y bajan solo mientras hay audio: es la señal de que la
     * emisora está sonando, aunque no haya nada que mirar. Cada una lleva su
     * propia altura y su propio ritmo para que no se vea un movimiento en bloque.
     */
    private fun startEqualizer() {
        if (!isRadio || eqAnimators.isNotEmpty()) return
        val alturas = listOf(0.35f, 0.85f, 0.5f, 1f, 0.6f)
        val duraciones = listOf(520L, 380L, 610L, 440L, 700L)

        binding.equalizer.post {
            // Entre el post y este momento el audio pudo haberse pausado
            if (eqAnimators.isNotEmpty() || player?.isPlaying != true) return@post
            for (i in 0 until binding.equalizer.childCount) {
                val barra = binding.equalizer.getChildAt(i)
                barra.pivotY = barra.height.toFloat()   // crecen desde abajo
                val animator = ObjectAnimator.ofFloat(
                    barra, View.SCALE_Y, 0.18f, alturas.getOrElse(i) { 0.7f }
                ).apply {
                    duration = duraciones.getOrElse(i) { 500L }
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    startDelay = i * 80L
                }
                eqAnimators.add(animator)
                animator.start()
            }
        }
    }

    private fun stopEqualizer() {
        eqAnimators.forEach { it.cancel() }
        eqAnimators.clear()
        for (i in 0 until binding.equalizer.childCount) {
            binding.equalizer.getChildAt(i).scaleY = 0.18f
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

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

    /**
     * En modo radio la lista es de emisoras, no de episodios: no se encadena
     * nada sola, se salta solo cuando el usuario toca anterior/siguiente.
     */
    private fun hasNextEpisode() = !isRadio && playlistIndex + 1 < playlistUrls.size

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
        btnNextEpisode?.visibility = if (hasNextEpisode()) View.VISIBLE else View.GONE
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

            if (isRadio) {
                exo.addListener(radioUiListener)
                updateRadioPlaybackState(true)
            }

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
