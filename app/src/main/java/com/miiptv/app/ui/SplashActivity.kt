package com.miiptv.app.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.miiptv.app.api.Session
import com.miiptv.app.util.Catalog
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.databinding.ActivitySplashBinding

/**
 * Pantalla de intro.
 * - Si existe res/raw/intro.mp4, lo reproduce a pantalla completa.
 * - Si no existe, muestra una animación por código, sin archivos externos.
 *
 * ---------------------------------------------------------------------------
 * SOBRE EL TIEMPO DE ARRANQUE
 *
 * La intro antes duraba 2200 ms **fijos**, y eran 2200 ms de nada: la app no
 * aprovechaba ese rato para hacer trabajo real, así que era tiempo de espera
 * puro sumado al arranque.
 *
 * Ahora hace dos cosas distintas:
 *
 *  1. La animación dura [INTRO_MS] (menos de la mitad) y es más suave: entra
 *     con un fundido corto en vez de un zoom marcado.
 *  2. Mientras se ve la intro, **se empieza a bajar el catálogo**. Antes eso
 *     arrancaba recién al abrir MainActivity, o sea después de la intro. Ahora
 *     las dos cosas pasan a la vez, y el Inicio suele estar listo cuando la
 *     intro termina.
 *
 * Sumado: la intro se ve, pero deja de costar tiempo.
 *
 * Además se puede tocar la pantalla para saltarla, cosa que antes solo
 * funcionaba con el video.
 * ---------------------------------------------------------------------------
 */
class SplashActivity : AppCompatActivity() {

    private companion object {
        /** Cuánto se ve la intro animada. Antes eran 2200 ms. */
        const val INTRO_MS = 900L
    }

    private var player: ExoPlayer? = null
    private var proceeded = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
        val binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        precargarCatalogo()

        // Tocar en cualquier lado saltea la intro
        binding.root.setOnClickListener { proceed() }

        val videoResId = resources.getIdentifier("intro", "raw", packageName)

        if (videoResId != 0) {
            playVideoIntro(binding, videoResId)
        } else {
            playAnimatedIntro(binding)
        }
    }

    /**
     * Arranca la descarga del catálogo mientras se ve la intro.
     *
     * Es lo que hace que la animación no cueste tiempo: el trabajo pesado del
     * arranque (bajar canales, películas y series del panel) pasa a ocurrir
     * DURANTE la intro en vez de después.
     *
     * [Catalog] es un objeto único que vive mientras vive el proceso, así que
     * lo que se baje acá lo encuentra MainActivity ya cargado. Se pasa una
     * función vacía como oyente: acá no hay nada que redibujar, y al no capturar
     * nada de la Activity no deja ninguna referencia colgada al cerrarse.
     */
    private fun precargarCatalogo() {
        if (!Session.isLoggedIn(this)) return
        Catalog.ensureLoaded(applicationContext) { }
    }

    private fun playVideoIntro(binding: ActivitySplashBinding, videoResId: Int) {
        binding.animatedIntro.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        binding.playerView.player = exo

        val uri = Uri.parse("android.resource://$packageName/$videoResId")
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = true

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) proceed()
            }
        })

        binding.playerView.setOnClickListener { proceed() } // tocar para saltar
        handler.postDelayed({ proceed() }, 12000) // por si el video no termina nunca
    }

    /**
     * Intro por código: un resplandor que se abre, el logo que aparece y el
     * nombre que sube. Todo en [INTRO_MS].
     *
     * La versión anterior arrancaba el logo al 60% de su tamaño y lo estiraba
     * hasta el 100%: un salto grande, que en pantallas chicas se veía brusco.
     * Ahora parte del 88%, así que se lee como que "se asienta" en lugar de
     * saltar. Es la diferencia entre notar la animación y sentirla.
     */
    private fun playAnimatedIntro(binding: ActivitySplashBinding) {
        val glow = binding.introGlow
        val logo = binding.ivLogoFallback
        val name = binding.tvAppName
        val by = binding.tvSplashBy

        logo.scaleX = 0.88f
        logo.scaleY = 0.88f
        name.translationY = 18f

        // Resplandor: se abre y se apaga solo, como un destello
        glow.scaleX = 0.55f
        glow.scaleY = 0.55f
        val glowIn = ObjectAnimator.ofFloat(glow, View.ALPHA, 0f, 0.55f).setDuration(360)
        val glowOut = ObjectAnimator.ofFloat(glow, View.ALPHA, 0.55f, 0f).setDuration(480)
        glowOut.startDelay = 360
        val glowX = ObjectAnimator.ofFloat(glow, View.SCALE_X, 0.55f, 1.3f).setDuration(840)
        val glowY = ObjectAnimator.ofFloat(glow, View.SCALE_Y, 0.55f, 1.3f).setDuration(840)

        val logoFade = ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f).setDuration(420)
        val logoZoomX = ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.88f, 1f).setDuration(520)
        val logoZoomY = ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.88f, 1f).setDuration(520)

        val nameFade = ObjectAnimator.ofFloat(name, View.ALPHA, 0f, 1f).setDuration(340)
        val nameSlide = ObjectAnimator.ofFloat(name, View.TRANSLATION_Y, 18f, 0f).setDuration(340)
        nameFade.startDelay = 170
        nameSlide.startDelay = 170

        val byFade = ObjectAnimator.ofFloat(by, View.ALPHA, 0f, 1f).setDuration(300)
        byFade.startDelay = 330

        AnimatorSet().apply {
            playTogether(
                glowIn, glowOut, glowX, glowY,
                logoFade, logoZoomX, logoZoomY,
                nameFade, nameSlide, byFade
            )
            interpolator = DecelerateInterpolator()
            start()
        }

        handler.postDelayed({ proceed() }, INTRO_MS)
    }

    private fun proceed() {
        if (proceeded) return
        proceeded = true
        val next = when {
            // Primera vez: elegir móvil o TV antes que nada
            !DeviceMode.isChosen(this) -> DeviceModeActivity::class.java
            Session.isLoggedIn(this) -> MainActivity::class.java
            else -> LoginActivity::class.java
        }
        startActivity(Intent(this, next))
        // Fundido entre la intro y la pantalla siguiente. Sin esto el cambio es
        // un corte seco que hace que la intro se sienta más larga de lo que es.
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }
}
