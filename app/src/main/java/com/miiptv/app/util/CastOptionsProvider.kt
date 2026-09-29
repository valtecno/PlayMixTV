package com.miiptv.app.util

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Le dice al SDK de Cast a qué "receptor" (la app que corre DENTRO del
 * Chromecast) mandar el video.
 *
 * Se usa el receptor por defecto de Google (DEFAULT_MEDIA_RECEIVER_
 * APPLICATION_ID) y no uno propio de PlayMix. Un receptor a medida (con el
 * logo de la app, colores propios, etc.) exige registrarlo en el Google Cast
 * SDK Developer Console con una cuenta de Google y una tarifa de una sola
 * vez -- no es algo que se pueda resolver escribiendo código, hace falta que
 * el dueño de la cuenta lo dé de alta a mano. Mientras tanto, el receptor por
 * defecto ya sabe reproducir HLS y MP4 sin configurar nada más.
 *
 * Registrada en el Manifest, como pide el SDK:
 *   <meta-data
 *       android:name="com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME"
 *       android:value="com.miiptv.app.util.CastOptionsProvider" />
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            // Al cortar el cast desde acá, que el receptor también corte del
            // todo (no lo deje "colgado" reproduciendo solo en el Chromecast).
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
