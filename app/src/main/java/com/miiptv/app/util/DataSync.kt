package com.miiptv.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.miiptv.app.api.ContentItem
import com.miiptv.app.api.ContentType
import com.miiptv.app.api.Session
import com.miiptv.app.api.SyncApi
import com.miiptv.app.api.SyncGetResponse
import com.miiptv.app.api.SyncPostResponse
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.concurrent.Executors

/**
 * Copia de seguridad en la nube de Favoritos, Continuar viendo e Historial.
 *
 * Antes esos tres vivían solo en SharedPreferences: reinstalar la app, cambiar
 * de equipo o simplemente borrar datos los perdía para siempre, sin aviso.
 *
 * Ahora, además de guardarse en el dispositivo como siempre, se suben —mejor
 * esfuerzo, sin bloquear nunca la pantalla ni el reproductor— al mismo
 * servidor propio que ya valida el código de acceso (ver PanelApi/api.php):
 * un endpoint nuevo, sync.php (ver server/sync.php.txt para una
 * implementación de referencia), que guarda y devuelve un blob de JSON por
 * cuenta.
 *
 * "Cuenta" es la misma clave (servidor + usuario Xtream) que ya usan
 * Favorites/ContinueWatching/History para separar sus archivos locales — no
 * hace falta ningún dato nuevo ni tocar el login. No hay nada sensible en el
 * blob: son los mismos canales/películas/series que ya se ven en la app.
 *
 * Flujo:
 *  - Cada cambio local (marcar favorito, avanzar un video, abrir algo) llama
 *    a [scheduleBackup], que espera un rato por si vienen más cambios
 *    seguidos y junta todo en una sola subida.
 *  - Al iniciar sesión, [restore] baja lo que haya en la nube, lo COMBINA con
 *    lo que ya hay en el equipo (nunca lo reemplaza) y sube el resultado, así
 *    los dos lados quedan iguales. En un equipo nuevo o recién reinstalado,
 *    "lo que ya hay" es simplemente nada, así que el efecto es restaurar todo.
 *  - Sin internet, o si el endpoint todavía no existe en el servidor, todo
 *    esto falla en silencio: la app sigue funcionando solo con lo local,
 *    igual que antes de este cambio.
 */
object DataSync {
    private val gson = Gson()
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var pendingPush: Runnable? = null

    /** Espera antes de subir, para juntar varios cambios seguidos en una sola subida. */
    private const val DEBOUNCE_MS = 8_000L

    data class Payload(
        val favoritos: List<ContentItem> = emptyList(),
        val continuar: List<ContinueWatching.Entry> = emptyList(),
        val historial: List<ContentItem> = emptyList(),
        val actualizado: Long = System.currentTimeMillis(),
        /**
         * Marcas de borrado ("TIPO:ID" -> cuándo se sacó), para que un
         * favorito o una entrada de Continuar viendo sacados en ESTE equipo
         * se saquen también en cualquier otro al sincronizar, en vez de que
         * "combinar" signifique "nunca borrar" (ver Favorites/
         * ContinueWatching.mergeFromRemote).
         */
        val favoritosBorrados: Map<String, Long> = emptyMap(),
        val continuarBorrados: Map<String, Long> = emptyMap(),
        /** Última vez que se vació el historial en este equipo (ver History.clear), o 0. */
        val historialBorradoEn: Long = 0L
    )

    private fun accountKey(context: Context): String {
        val server = Session.server(context)
        val user = Session.username(context)
        return "$server|$user".replace(Regex("[^A-Za-z0-9]"), "_").take(80)
    }

    private fun haySesion(context: Context) =
        Session.server(context).isNotBlank() && Session.username(context).isNotBlank()

    /**
     * El perfil de Niños NO participa en la sincronización con la nube.
     *
     * La nube guarda un único blob por cuenta (servidor + usuario), sin
     * distinguir perfiles. Si se sincronizara mientras el perfil kids está
     * activo, los favoritos del adulto (que viven en ese blob) se mezclarían
     * con los del niño — exactamente lo que se quiere evitar. Los datos del
     * perfil Niños son exclusivamente locales.
     */
    private fun esPerfilNinos(context: Context): Boolean =
        Profiles.active(context)?.isKids == true

    // ---------------- Última sincronización (para mostrarla en Ajustes) ----------------

    private const val PREFS_ESTADO = "miiptv_datasync_estado"
    private const val KEY_ULTIMA = "ultima_ok"

    /**
     * Momento (epoch ms) de la última subida exitosa DE LA CUENTA ACTIVA, o 0
     * si nunca hubo una. Con cuenta en la clave (igual que Favorites/
     * ContinueWatching/History): sin esto, sincronizar el Sistema L y
     * después cambiar al Sistema XL (que puede no haberse sincronizado
     * nunca) seguía mostrando la fecha del otro sistema en Ajustes.
     */
    fun lastSyncAt(context: Context): Long =
        context.getSharedPreferences(PREFS_ESTADO, Context.MODE_PRIVATE)
            .getLong(KEY_ULTIMA + "_" + accountKey(context), 0L)

    private fun marcarSincronizado(context: Context) {
        context.getSharedPreferences(PREFS_ESTADO, Context.MODE_PRIVATE)
            .edit().putLong(KEY_ULTIMA + "_" + accountKey(context), System.currentTimeMillis()).apply()
    }

    /**
     * Pide subir los datos actuales, pero espera [DEBOUNCE_MS] por si viene
     * otro cambio enseguida (marcar varios favoritos seguidos no debería ser
     * varias subidas). Se llama desde Favorites/ContinueWatching/History
     * después de cada escritura; no hace falta llamarla a mano.
     */
    /** Cancela una subida pendiente (al cambiar de perfil: no subir datos del perfil viejo). */
    @Synchronized
    fun cancelPending() {
        pendingPush?.let { ui.removeCallbacks(it) }
        pendingPush = null
    }

    @Synchronized
    fun scheduleBackup(context: Context) {
        if (!haySesion(context) || esPerfilNinos(context)) return
        val app = context.applicationContext
        val cuenta = accountKey(context)
        pendingPush?.let { ui.removeCallbacks(it) }
        // Se guarda la cuenta activa al momento de programar. Si para cuando
        // se cumplen los 8s la cuenta activa cambió (el usuario cambió de
        // cuenta/cerró sesión mientras tanto), Favorites/ContinueWatching/
        // History.getAll(context) ya estarían leyendo la cuenta NUEVA, así
        // que subir en ese momento mandaría datos de la cuenta nueva bajo la
        // cuenta vieja. Mejor no subir nada en ese caso (ver backupNow) que
        // mezclar cuentas; el próximo cambio en la cuenta vieja programará
        // otra subida correcta.
        val task = Runnable { backupNow(app, cuentaEsperada = cuenta) }
        pendingPush = task
        ui.postDelayed(task, DEBOUNCE_MS)
    }

    /**
     * Sube ya mismo, sin esperar el debounce. Mejor esfuerzo: un fallo acá no
     * interrumpe nada.
     *
     * [cuentaEsperada] lo usa [scheduleBackup] para pasar la cuenta que
     * estaba activa cuando se programó esta subida: si para cuando se
     * ejecuta la cuenta activa ya es otra, se cancela en vez de subir datos
     * de la cuenta nueva contra la clave de la vieja.
     */
    fun backupNow(context: Context, cuentaEsperada: String? = null, onDone: (Boolean) -> Unit = {}) {
        if (!haySesion(context) || esPerfilNinos(context)) {
            onDone(false)
            return
        }
        val cuenta = accountKey(context)
        if (cuentaEsperada != null && cuentaEsperada != cuenta) {
            onDone(false)
            return
        }
        worker.execute {
            val payload = Payload(
                favoritos = Favorites.getAll(context),
                continuar = ContinueWatching.list(context, ContentType.MOVIE) +
                    ContinueWatching.list(context, ContentType.SERIES),
                historial = History.getAll(context),
                favoritosBorrados = Favorites.tombstones(context),
                continuarBorrados = ContinueWatching.tombstones(context),
                historialBorradoEn = History.clearedAt(context)
            )
            val json = gson.toJson(payload)
            SyncApi.instance.guardar(cuenta = cuenta, datos = json).enqueue(object : Callback<SyncPostResponse> {
                override fun onResponse(call: Call<SyncPostResponse>, response: Response<SyncPostResponse>) {
                    if (response.isSuccessful) marcarSincronizado(context)
                    onDone(response.isSuccessful)
                }

                override fun onFailure(call: Call<SyncPostResponse>, t: Throwable) {
                    onDone(false)
                }
            })
        }
    }

    /**
     * Trae lo que haya guardado en la nube y lo combina con lo que ya está en
     * este equipo (no lo reemplaza: algo marcado acá mismo, antes de que
     * termine de bajar, no se pierde). Al terminar sube el resultado
     * combinado, para que la nube quede igual que el equipo.
     *
     * [onDone] se llama siempre —haya ido bien, mal, o no haya sesión— para
     * que quien llame (típicamente LoginActivity, antes de abrir el Inicio)
     * pueda seguir sin quedarse esperando para siempre. Su parámetro dice si
     * el viaje a la nube salió bien (true) o no (false, típicamente sin
     * internet); "sin sesión" no cuenta como falla, porque no había nada que
     * hacer. Nadie está obligado a mirar ese valor —LoginActivity y
     * SettingsActivity lo ignoran, porque para ellas restaurar es mejor
     * esfuerzo y navegan igual— pero el botón manual de Mi Espacio lo usa
     * para avisar si realmente no se pudo actualizar.
     */
    fun restore(context: Context, onDone: (ok: Boolean) -> Unit) {
        if (!haySesion(context) || esPerfilNinos(context)) {
            onDone(true)
            return
        }
        val cuenta = accountKey(context)
        SyncApi.instance.obtener(cuenta = cuenta).enqueue(object : Callback<SyncGetResponse> {
            override fun onResponse(call: Call<SyncGetResponse>, response: Response<SyncGetResponse>) {
                val json = response.body()?.datos
                if (!response.isSuccessful) {
                    onDone(false)
                    return
                }
                if (json.isNullOrBlank()) {
                    // Nada en la nube todavía (cuenta nueva, o recién
                    // subiendo este código por primera vez a un usuario que
                    // ya tenía favoritos/historial locales). Sin esto, esos
                    // datos locales quedaban solo en el equipo hasta el
                    // próximo cambio manual: exactamente lo que esta función
                    // existe para evitar.
                    backupNow(context) { ok -> ui.post { onDone(ok) } }
                    return
                }
                worker.execute {
                    val remoto = runCatching { gson.fromJson(json, Payload::class.java) }.getOrNull()
                    if (remoto != null) {
                        // Gson arma esto con reflection (Unsafe.allocateInstance),
                        // sin pasar por el constructor de Kotlin: un blob viejo en
                        // la nube -guardado por una versión de la app anterior a
                        // este cambio, que no tenía estos tres campos- deja acá
                        // NULL en tiempo de ejecución pese a que el tipo declarado
                        // es no-nulable (List/Map "no nulos" de Kotlin no protegen
                        // de esto). Sin el orEmpty()/?: acá, el primer sync de
                        // cualquier cuenta que ya sincronizaba antes de este
                        // cambio reventaba con NullPointerException.
                        Favorites.mergeFromRemote(
                            context, remoto.favoritos.orEmpty(), remoto.favoritosBorrados.orEmpty()
                        )
                        ContinueWatching.mergeFromRemote(
                            context, remoto.continuar.orEmpty(), remoto.continuarBorrados.orEmpty()
                        )
                        History.mergeFromRemote(
                            context, remoto.historial.orEmpty(), remoto.historialBorradoEn ?: 0L
                        )
                        backupNow(context)
                    }
                    ui.post { onDone(remoto != null) }
                }
            }

            override fun onFailure(call: Call<SyncGetResponse>, t: Throwable) {
                onDone(false)
            }
        })
    }
}
