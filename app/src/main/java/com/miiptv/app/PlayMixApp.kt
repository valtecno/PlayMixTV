package com.miiptv.app

import android.app.Application
import com.miiptv.app.util.ImageLoader

/**
 * Punto de arranque del proceso. Existe para configurar Picasso (ver
 * [ImageLoader]) antes que cualquier pantalla: Android puede reabrir la app
 * directo en MainActivity o en el reproductor (sin pasar por Splash), así que
 * inicializarlo en una Activity no alcanzaba.
 */
class PlayMixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ImageLoader.init(this)
    }
}
