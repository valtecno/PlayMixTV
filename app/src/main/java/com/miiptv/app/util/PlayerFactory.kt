package com.miiptv.app.util

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
     * @param handleAudioFocus dejar en false cuando hay varios reproductores a la vez
     *        (multi-pantalla), porque si todos piden el foco se pausan entre ellos.
     */
    fun build(
        context: Context,
        loadControl: LoadControl? = null,
        handleAudioFocus: Boolean = true
    ): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Session.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)   // muchos servidores redirigen http <-> https
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

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
            setParameters(
                buildUponParameters()
                    // No descartar pistas por falta de soporte declarado: intentar igual
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setExceedAudioConstraintsIfNecessary(true)
                    .setExceedRendererCapabilitiesIfNecessary(true)
            )
        }

        val builder = ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory, extractors()))

        if (loadControl != null) builder.setLoadControl(loadControl)

        return builder.build().apply {
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
