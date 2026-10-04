package com.miiptv.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.miiptv.app.R
import com.miiptv.app.databinding.ActivityIntroBinding
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.util.IntroTimeline

/**
 * Intro de PlayMix TV: se ve después de elegir perfil y antes de entrar a la app.
 *
 * Dura [IntroTimeline.DURATION] (3,6 s) con sonido. Se salta tocando la pantalla,
 * con OK/Enter del control remoto o con Atrás. Las curvas de la animación viven en
 * [IntroTimeline]; acá solo se aplican a las vistas y se sincroniza el sonido.
 */
class IntroActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_WELCOME = "ir_a_bienvenida"

        fun start(context: Context, irABienvenida: Boolean) {
            context.startActivity(
                Intent(context, IntroActivity::class.java).putExtra(EXTRA_WELCOME, irABienvenida)
            )
        }
    }

    private lateinit var binding: ActivityIntroBinding
    private var animator: ValueAnimator? = null
    private var player: MediaPlayer? = null
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
        binding = ActivityIntroBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.introRoot.setOnClickListener { proceed() }
        medir()
        prepararSonido()

        animator = ValueAnimator.ofFloat(0f, IntroTimeline.DURATION).apply {
            duration = (IntroTimeline.DURATION * 1000).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { aplicar(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = proceed()
            })
        }
        aplicar(0f)
        // Imagen y sonido arrancan en el mismo momento
        player?.start()
        animator?.start()
    }

    /** Tamaños según la pantalla: el logo ocupa lo mismo en celular y en TV. */
    private fun medir() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val logo = minOf(w * 0.55f, h * 0.42f).toInt()
        fun View.size(width: Int, height: Int) {
            layoutParams = (layoutParams as FrameLayout.LayoutParams).also {
                it.width = width; it.height = height
            }
        }
        binding.introLogo.size(logo, logo)
        binding.introGlow.size((logo * 1.9f).toInt(), (logo * 1.9f).toInt())
        binding.introRing.size(logo, logo)
        binding.introLine.size((w * 0.8f).toInt(), (2 * dm.density).toInt().coerceAtLeast(2))
    }

    private fun prepararSonido() {
        player = runCatching {
            MediaPlayer.create(this, R.raw.intro_sonido)?.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        }.getOrNull()   // sin sonido la intro funciona igual
    }

    private fun aplicar(t: Float) {
        binding.introGlow.apply {
            alpha = IntroTimeline.glowAlpha(t); scaleX = IntroTimeline.glowScale(t); scaleY = scaleX
        }
        binding.introLine.apply {
            alpha = IntroTimeline.lineAlpha(t); scaleX = IntroTimeline.lineScaleX(t)
        }
        binding.introRing.apply {
            alpha = IntroTimeline.ringAlpha(t); scaleX = IntroTimeline.ringScale(t); scaleY = scaleX
        }
        binding.introLogo.apply {
            alpha = IntroTimeline.logoAlpha(t); scaleX = IntroTimeline.logoScale(t); scaleY = scaleX
            sweep = IntroTimeline.sweep(t)
        }
        binding.introFlash.alpha = IntroTimeline.flashAlpha(t)
    }

    private fun proceed() {
        if (done) return
        done = true
        animator?.removeAllListeners()
        animator?.cancel()
        liberarSonido()
        val destino = if (intent.getBooleanExtra(EXTRA_WELCOME, false))
            WelcomeActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, destino))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun liberarSonido() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
        ) {
            proceed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        if (!done) {
            animator?.pause()
            player?.let { runCatching { if (it.isPlaying) it.pause() } }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!done) {
            animator?.resume()
            player?.let { runCatching { it.start() } }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        animator?.removeAllListeners()
        animator?.cancel()
        liberarSonido()
    }
}
