package com.miiptv.app.util

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.miiptv.app.R

/**
 * Recordatorios de EPG: el usuario puede marcar un programa del EPG y recibir
 * una notificación del sistema cuando está por empezar.
 *
 * Cada recordatorio es una alarma de Android que dispara un BroadcastReceiver
 * que a su vez muestra la notificación. Las alarmas sobreviven al cierre de la
 * app (pero no a un reinicio del dispositivo, lo cual es el comportamiento
 * esperado para recordatorios de TV en vivo).
 */
object EpgReminder {

    private const val CHANNEL_ID    = "playmix_epg_reminders"
    private const val PREFS         = "miiptv_epg_reminders"
    private const val AVISO_ANTES_MS = 2 * 60 * 1000L   // avisar 2 minutos antes

    // ---- Extras del Intent que recibe el BroadcastReceiver ----
    const val EXTRA_CANAL   = "epg_canal"
    const val EXTRA_TITULO  = "epg_titulo"
    const val EXTRA_HORA    = "epg_hora"
    const val EXTRA_ID      = "epg_reminder_id"

    /** Crea el canal de notificaciones (obligatorio en Android 8+). */
    fun crearCanal(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val canal = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.epg_reminder_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.epg_reminder_channel_desc)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(canal)
    }

    /**
     * Programa un recordatorio para el programa indicado.
     *
     * @param canalNombre  Nombre del canal (ej: "ESPN").
     * @param programaTitulo  Título del programa (ej: "Champions League").
     * @param inicioMs  Hora de inicio en milisegundos (epoch).
     * @return true si la alarma se pudo programar.
     */
    fun programar(
        context: Context,
        canalNombre: String,
        programaTitulo: String,
        inicioMs: Long
    ): Boolean {
        val disparo = inicioMs - AVISO_ANTES_MS
        if (disparo <= System.currentTimeMillis()) return false   // ya pasó

        val id = (canalNombre + programaTitulo + inicioMs).hashCode()
        val intent = Intent(context, RecordatorioReceiver::class.java).apply {
            putExtra(EXTRA_ID,     id)
            putExtra(EXTRA_CANAL,  canalNombre)
            putExtra(EXTRA_TITULO, programaTitulo)
            putExtra(EXTRA_HORA,   android.text.format.DateFormat.getTimeFormat(context)
                .format(java.util.Date(inicioMs)))
        }
        val pi = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.set(AlarmManager.RTC_WAKEUP, disparo, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, disparo, pi)
            }
        }.onFailure { return false }

        // Guardar el id para poder cancelarlo
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("reminder_$id", id).apply()

        return true
    }

    /** Cancela un recordatorio programado. */
    fun cancelar(context: Context, id: Int) {
        val intent = Intent(context, RecordatorioReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("reminder_$id").apply()
    }

    /** Devuelve true si ya hay un recordatorio activo para este programa. */
    fun estaActivo(context: Context, canalNombre: String, programaTitulo: String, inicioMs: Long): Boolean {
        val id = (canalNombre + programaTitulo + inicioMs).hashCode()
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("reminder_$id")
    }
}

/**
 * Receptor de la alarma: muestra la notificación cuando llega la hora.
 * Se registra en el Manifest como un BroadcastReceiver estándar.
 */
class RecordatorioReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id     = intent.getIntExtra(EpgReminder.EXTRA_ID, 0)
        val canal  = intent.getStringExtra(EpgReminder.EXTRA_CANAL) ?: return
        val titulo = intent.getStringExtra(EpgReminder.EXTRA_TITULO) ?: return
        val hora   = intent.getStringExtra(EpgReminder.EXTRA_HORA) ?: ""

        EpgReminder.crearCanal(context)

        val notif = NotificationCompat.Builder(context, "playmix_epg_reminders")
            .setSmallIcon(R.drawable.ic_live)
            .setContentTitle("$canal — $titulo")
            .setContentText(context.getString(R.string.epg_reminder_body, hora))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(id, notif)
        }

        // Limpiar de preferencias
        context.getSharedPreferences("miiptv_epg_reminders", Context.MODE_PRIVATE)
            .edit().remove("reminder_$id").apply()
    }
}
