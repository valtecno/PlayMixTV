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
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.databinding.ActivitySplashBinding

/**
 * Pantalla de intro.
 * - Si existe res/raw/intro.mp4, lo reproduce a pantalla completa.
 * - Si no existe, muestra una animación por código: el logo aparece con
 *   fade + zoom, y debajo el nombre de la app ("PlayMix TV") se desliza
 *   hacia arriba con fade. Sin depender de ningún archivo externo.
 */
class SplashActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var proceeded = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
        val binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoResId = resources.getIdentifier("intro", "raw", packageName)

        if (videoResId != 0) {
            playVideoIntro(binding, videoResId)
        } else {
            playAnimatedIntro(binding)
        }
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

    private fun playAnimatedIntro(binding: ActivitySplashBinding) {
        val logo = binding.ivLogoFallback
        val name = binding.tvAppName

        logo.scaleX = 0.6f
        logo.scaleY = 0.6f
        name.translationY = 40f

        val logoFade = ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f).setDuration(500)
        val logoZoomX = ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.6f, 1f).setDuration(500)
        val logoZoomY = ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.6f, 1f).setDuration(500)

        val nameFade = ObjectAnimator.ofFloat(name, View.ALPHA, 0f, 1f).setDuration(450)
        val nameSlide = ObjectAnimator.ofFloat(name, View.TRANSLATION_Y, 40f, 0f).setDuration(450)
        nameFade.startDelay = 250
        nameSlide.startDelay = 250

        // El crédito entra último, más suave
        val by = binding.tvSplashBy
        val byFade = ObjectAnimator.ofFloat(by, View.ALPHA, 0f, 1f).setDuration(400)
        byFade.startDelay = 600

        AnimatorSet().apply {
            playTogether(logoFade, logoZoomX, logoZoomY, nameFade, nameSlide, byFade)
            interpolator = DecelerateInterpolator()
            start()
        }

        handler.postDelayed({ proceed() }, 2200)
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
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }
}
