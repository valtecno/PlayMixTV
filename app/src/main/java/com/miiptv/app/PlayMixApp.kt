package com.miiptv.app

import android.app.Application
import com.miiptv.app.util.CrashLogger
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
        // Primero que nada: si algo más abajo en este mismo onCreate llegara
        // a romper, igual queda registrado (ver CrashLogger).
        CrashLogger.install(this)
        ImageLoader.init(this)
        // Fondo de pantallas elegido en Personalizar → Fondo de la app. Se
        // aplica a cada pantalla al crearse y al volver a primer plano, así un
        // cambio se ve también en las que quedaron atrás en la pila.
        registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            private fun aplicar(activity: android.app.Activity) {
                runCatching {
                    com.miiptv.app.util.Appearance.screenBackground(activity)
                        ?.let { activity.window.setBackgroundDrawable(it) }
                }
            }
            override fun onActivityCreated(a: android.app.Activity, s: android.os.Bundle?) {
                com.miiptv.app.util.SleepTimer.registrar(a)
                aplicar(a)
            }
            override fun onActivityResumed(a: android.app.Activity) = aplicar(a)
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, o: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {
                com.miiptv.app.util.SleepTimer.olvidar(a)
            }
        })
    }

    /**
     * El sistema avisa que falta memoria (o que la app quedó en segundo plano):
     * se sueltan las imágenes decodificadas, que se recuperan del disco al volver.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) ImageLoader.liberarMemoria()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ImageLoader.liberarMemoria()
    }
}
