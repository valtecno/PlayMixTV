package com.miiptv.app.util

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.util.WeakHashMap
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.miiptv.app.api.Session

/**
 * Construye reproductores con la mayor compatibilidad posible de códecs y
 * contenedores, y que se identifican como "PlayMix TV - VIP".
 */
object PlayerFactory {

    /**
     * Reintentos de descarga pensados para IPTV.
     *
     * Con los valores de ExoPlayer, una lista .m3u8 o un segmento que fallaba
     * 3 veces seguidas (un panel lento, un corte de unos segundos) daba un
     * error fatal: el reproductor se detenía, se perdía todo lo que ya estaba
     * en el búfer y la pantalla reconectaba desde cero (corte visible).
     *
     * Mientras el canal ya está reproduciendo, se insiste bastante más y con
     * esperas cortas: el búfer (15 s o más) cubre la espera y el corte del
     * panel no se nota. Antes de que arranque se mantienen los valores de
     * siempre, para no demorar el aviso de un canal que de verdad no anda.
     */
    class PoliticaErroresIptv : DefaultLoadErrorHandlingPolicy() {
        @Volatile var reproduciendo = false

        override fun getMinimumLoadableRetryCount(dataType: Int): Int {
            if (!reproduciendo) return super.getMinimumLoadableRetryCount(dataType)
            return when (dataType) {
                C.DATA_TYPE_MANIFEST -> 10
                C.DATA_TYPE_MEDIA, C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE -> 10
                else -> super.getMinimumLoadableRetryCount(dataType)
            }
        }

        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val base = super.getRetryDelayMsFor(loadErrorInfo)
            if (base == C.TIME_UNSET || !reproduciendo) return base
            return minOf(loadErrorInfo.errorCount * 700L, 2_500L)
        }
    }

    /** Política de cada reproductor creado acá (para las fuentes armadas a mano). */
    private val politicas = WeakHashMap<Player, PoliticaErroresIptv>()

    private fun httpFactory(lecturaMs: Int = 15_000): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent(Session.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)   // muchos servidores redirigen http <-> https
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(lecturaMs)

    /** ¿Es un canal en vivo por .ts (formato Xtream: /live/usuario/clave/123.ts)? */
    fun esVivoTs(url: String): Boolean = VIVO_TS.containsMatchIn(url)

    private val VIVO_TS = Regex("/live/[^/]+/[^/]+/\\d+\\.ts(\\?.*)?$")

    /**
     * Carga [url] en [exo]. Los canales .ts en vivo van con
     * [DataSourceVivoContinuo] (sobrevive a los cortes del panel); todo lo
     * demás, como siempre. [posicionMs] = C.TIME_UNSET para la posición por
     * defecto (el vivo, o el principio).
     */
    fun cargar(exo: ExoPlayer, url: String, posicionMs: Long = C.TIME_UNSET) {
        if (esVivoTs(url)) {
            // Lectura de 8 s: si la conexión queda colgada sin datos, se nota
            // antes y se reconecta en vez de congelar la imagen 15 s.
            val http = httpFactory(lecturaMs = 8_000)
            val fuente: MediaSource = ProgressiveMediaSource.Factory({ DataSourceVivoContinuo(http) }, extractors())
                .setLoadErrorHandlingPolicy(politicas[exo] ?: PoliticaErroresIptv())
                .createMediaSource(MediaItem.fromUri(url))
            exo.setMediaSource(fuente, posicionMs)
        } else {
            exo.setMediaItem(MediaItem.fromUri(url), posicionMs)
        }
    }

    /**
     * El canal quedó atrás de lo que el panel todavía guarda (pasa tras un
     * corte largo): se vuelve al vivo al instante, sin rearmar nada. Devuelve
     * true si era ese caso.
     */
    fun volverAlVivoSiQuedoAtras(exo: ExoPlayer, error: PlaybackException): Boolean {
        if (error.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return false
        exo.seekToDefaultPosition()
        exo.prepare()
        return true
    }

    /**
     * Extractores configurados para streams IPTV reales:
     *  - TS: habilita la detección de pistas de audio AC-3/E-AC-3/HE-AAC y de
     *    subtítulos incrustados, que por defecto ExoPlayer no busca.
     *  - Permite streams TS sin marca de duración (típico de canales en vivo).
     */
    private fun extractors() = DefaultExtractorsFactory()
        .setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
        )
        .setConstantBitrateSeekingEnabled(true)

    /**
     * Buffer para reproductores secundarios (vista previa, multipantalla). El
     * de ExoPlayer por defecto puede reservar más de 100 MB POR reproductor, y
     * en multipantalla hay cuatro a la vez: en un equipo con poca RAM eso era
     * pedir a gritos que el sistema matara la app.
     */
    fun loadControlAcotado(context: Context, bytes: Int = 12 * 1024 * 1024): LoadControl =
        androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_500, 15_000, 1_500, 2_500)
            .setTargetBufferBytes(bytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

    /**
     * @param handleAudioFocus dejar en false cuando hay varios reproductores a la vez
     *        (multi-pantalla), porque si todos piden el foco se pausan entre ellos.
     * @param applyLanguagePrefs aplica el idioma de audio y subtítulos elegidos en
     *        Ajustes → Cuenta → Audio. Se deja en false para Canales/PPV (donde ya
     *        existe su propia detección de audio en vivo, ver probarAudioAlternativo
     *        en PlayerActivity) y en true solo para Películas/Series.
     * @param applyAudioPref aplica solo el idioma de audio preferido (sin tocar los
     *        subtítulos). Lo usan los canales en vivo; Películas/Series ya lo
     *        incluyen con applyLanguagePrefs.
     */
    fun build(
        context: Context,
        loadControl: LoadControl? = null,
        handleAudioFocus: Boolean = true,
        applyLanguagePrefs: Boolean = false,
        applyAudioPref: Boolean = false
    ): ExoPlayer {
        val http = httpFactory()
        val politica = PoliticaErroresIptv()

        /**
         * PREFER_EXTENSION + decoderFallback:
         *  - Si el aparato trae decodificadores por software adicionales, los usa.
         *  - Si el decodificador principal falla al inicializarse (pasa seguido con
         *    perfiles raros de H.264/HEVC), prueba automáticamente con el siguiente
         *    en vez de cortar la reproducción con un error.
         */
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)

        val trackSelector = DefaultTrackSelector(context).apply {
            val parametros = buildUponParameters()
                // No descartar pistas por falta de soporte declarado: intentar igual
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedAudioConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)

            if (applyLanguagePrefs || applyAudioPref) {
                PlayerPrefs.getAudioLanguage(context)?.let { parametros.setPreferredAudioLanguage(it) }
            }
            if (applyLanguagePrefs) {
                when (PlayerPrefs.getSubtitleMode(context)) {
                    PlayerPrefs.SUB_SPANISH ->
                        parametros.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage("spa")
                    PlayerPrefs.SUB_ENGLISH ->
                        parametros.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage("eng")
                    PlayerPrefs.SUB_AUTO ->
                        parametros.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    else -> // SUB_OFF, y cualquier valor viejo/inesperado guardado
                        parametros.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                }
            }

            setParameters(parametros)
        }

        val builder = ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(http, extractors()).setLoadErrorHandlingPolicy(politica)
            )
            // Salto de los botones adelantar/retroceder del reproductor (10s).
            // No es un atributo del layout: se define acá, a nivel de reproductor.
            .setSeekForwardIncrementMs(10_000)
            .setSeekBackIncrementMs(10_000)

        builder.setLoadControl(loadControl ?: loadControlAcotado(context))

        return builder.build().apply {
            politicas[this] = politica
            // La política se pone paciente recién cuando el contenido ya se ve.
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> politica.reproduciendo = true
                        Player.STATE_IDLE -> politica.reproduciendo = false
                    }
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    politica.reproduciendo = false
                }
            })
            // Con la pantalla apagada (radio y audio en segundo plano) el CPU y el
            // Wi-Fi entran en ahorro y el stream se cortaba. WAKE_LOCK ya estaba
            // declarado en el manifest, pero nunca se usaba.
            setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            // Foco de audio: se pausa solo con una llamada y baja el volumen con avisos
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                handleAudioFocus
            )
            // Pausa al desenchufar auriculares, en vez de sonar por el parlante
            setHandleAudioBecomingNoisy(handleAudioFocus)
        }
    }
}
