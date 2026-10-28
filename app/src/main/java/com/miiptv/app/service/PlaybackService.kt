package com.miiptv.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.miiptv.app.R
import com.miiptv.app.ui.PlayerActivity
import com.miiptv.app.util.PlaybackHolder

/**
 * Servicio en primer plano que mantiene sonando lo que se está reproduciendo
 * cuando la app pasa a segundo plano. Android mata los procesos sin un servicio
 * de este tipo, así que sin esto las radios se cortarían al salir.
 */
class PlaybackService : Service() {

    companion object {
        private const val CHANNEL_ID = "playmix_playback"
        private const val NOTIF_ID = 4210

        const val ACTION_TOGGLE = "com.miiptv.app.TOGGLE"
        const val ACTION_STOP = "com.miiptv.app.STOP"

        /**
         * true entre startForegroundService() y el momento en que el servicio
         * llama a startForeground(). Android exige que ese llamado ocurra SÍ O
         * SÍ: si se detiene el servicio antes (stopService), en Android 12+ la
         * app se cierra con ForegroundServiceDidNotStartInTimeException.
         */
        @Volatile private var arrancando = false

        fun start(context: Context) {
            arrancando = true
            try {
                ContextCompat.startForegroundService(
                    context, Intent(context, PlaybackService::class.java)
                )
            } catch (e: RuntimeException) {
                arrancando = false   // no llegó a arrancar: nada que esperar
                throw e
            }
        }

        fun stop(context: Context) {
            if (arrancando) {
                // Todavía no llamó a startForeground(): en vez de cortarlo de
                // golpe se le pide que se detenga él mismo, después de cumplir.
                val pedido = Intent(context, PlaybackService::class.java).setAction(ACTION_STOP_SILENCIOSO)
                val enviado = runCatching { context.startService(pedido) }.isSuccess
                if (enviado) return
            }
            context.stopService(Intent(context, PlaybackService::class.java))
        }

        /** Detener sin soltar el reproductor (la pantalla lo retoma). */
        private const val ACTION_STOP_SILENCIOSO = "com.miiptv.app.STOP_QUIET"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Lo PRIMERO, pase lo que pase: pasar a primer plano. Si el servicio se
        // arrancó con startForegroundService() y termina sin esto (porque el
        // reproductor ya no existe, o porque se pidió detenerlo enseguida),
        // Android cierra la app con ForegroundServiceDidNotStartInTimeException.
        val enPrimerPlano = runCatching { pasarAPrimerPlano() }.isSuccess
        arrancando = false

        when (intent?.action) {
            ACTION_TOGGLE -> PlaybackHolder.togglePlayPause()
            ACTION_STOP -> {
                PlaybackHolder.release()
                detener()
                return START_NOT_STICKY
            }
            ACTION_STOP_SILENCIOSO -> {
                detener()
                return START_NOT_STICKY
            }
        }

        if (!enPrimerPlano || PlaybackHolder.player == null) {
            detener()
            return START_NOT_STICKY
        }

        // Refresca la notificación (p. ej. pausado/sonando tras ACTION_TOGGLE)
        runCatching { pasarAPrimerPlano() }
        return START_NOT_STICKY
    }

    private fun pasarAPrimerPlano() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun detener() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_channel),
            NotificationManager.IMPORTANCE_LOW   // sin sonido ni vibración
        ).apply {
            description = getString(R.string.playback_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(PlayerActivity.EXTRA_URL, PlaybackHolder.currentUrl)
                .putExtra(PlayerActivity.EXTRA_TITLE, PlaybackHolder.currentTitle),
            pendingFlags()
        )

        val sonando = PlaybackHolder.isPlaying

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_live)
            .setContentTitle(PlaybackHolder.currentTitle.ifBlank { getString(R.string.app_name) })
            .setContentText(getString(if (sonando) R.string.playback_playing else R.string.playback_paused))
            .setContentIntent(abrir)
            .setOngoing(sonando)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                getString(if (sonando) R.string.playback_pause else R.string.playback_resume),
                servicePendingIntent(ACTION_TOGGLE, 1)
            )
            .addAction(0, getString(R.string.playback_stop), servicePendingIntent(ACTION_STOP, 2))
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            pendingFlags()
        )

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    override fun onDestroy() {
        arrancando = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
