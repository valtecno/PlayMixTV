package com.miiptv.app.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.miiptv.app.R
import com.miiptv.app.api.Category
import com.miiptv.app.api.ContentItem
import com.miiptv.app.api.LiveStream
import com.miiptv.app.api.Session
import com.miiptv.app.api.toContentItem
import com.miiptv.app.databinding.ActivityMultiscreenBinding
import com.miiptv.app.databinding.DialogChannelPickerBinding
import com.miiptv.app.databinding.ItemCategoryBinding
import com.miiptv.app.databinding.ItemChannelBinding
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.Catalog
import com.miiptv.app.util.KidsFilter
import com.miiptv.app.util.KidsMode
import com.miiptv.app.util.Parental
import com.miiptv.app.util.PlayerFactory
import com.squareup.picasso.Picasso
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Muestra 4 canales en vivo a la vez. Tocar un recuadro deja ese canal con el
 * audio activado (el resto queda en mute). Mantener presionado abre el selector
 * de canal con buscador.
 *
 * ---------------------------------------------------------------------------
 * POR QUÉ SE REESCRIBIÓ
 *
 * En el Sistema XL la pantalla quedaba completamente en negro. Eran tres fallas
 * distintas sumadas:
 *
 *  1. Pedía `get_live_streams` SIN categoría, o sea el listado completo de
 *     canales del panel: decenas de MB que en XL no llegaban a tiempo. Canales
 *     sí funcionaba porque siempre pide de a una categoría. Esa era la
 *     diferencia. Ahora la multi-pantalla hace lo mismo: primero las categorías
 *     (respuesta de pocos KB, instantánea) y después una sola categoría.
 *
 *  2. `onStop()` liberaba los cuatro reproductores pero nada los volvía a
 *     construir. Al salir a Inicio y volver, la activity seguía viva, no se
 *     ejecutaba `onCreate` otra vez, y quedaban cuatro ExoPlayer ya liberados:
 *     negro permanente. Ahora se construyen en `onStart` y se liberan en
 *     `onStop`, restaurando las asignaciones.
 *
 *  3. Ningún reproductor tenía listener de error. Si el panel rechazaba la
 *     conexión (muy común: las cuentas Xtream limitan las conexiones
 *     simultáneas, y acá se abren cuatro de golpe) el recuadro quedaba negro
 *     sin decir nada. Ahora cada recuadro informa su propio error.
 * ---------------------------------------------------------------------------
 */
class MultiScreenActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "multiscreen_prefs"
        private const val KEY_FORMAT = "format"
        private const val SLOTS = 4

        /** Formatos que suele servir un panel Xtream para un canal en vivo. */
        private val FORMATS = listOf("m3u8", "ts")

    }

    private lateinit var binding: ActivityMultiscreenBinding

    private val players = arrayOfNulls<ExoPlayer>(SLOTS)
    private val listeners = arrayOfNulls<Player.Listener>(SLOTS)

    /** Canal asignado a cada recuadro. Sobrevive al ciclo onStop/onStart. */
    private val assigned = arrayOfNulls<ContentItem>(SLOTS)

    /** Canales de la categoría abierta ahora mismo: es la base del buscador. */
    private var channels: List<ContentItem> = emptyList()
    private var categories: List<Category> = emptyList()
    private var currentCategoryId: String? = null

    private var activeSlot = 0
    private var formatIndex = 0
    private var kidsMode = false
    private var picker: Dialog? = null


    private lateinit var slotViews: List<ViewGroup>
    private lateinit var playerViews: List<PlayerView>
    private lateinit var progressViews: List<ProgressBar>
    private lateinit var labelViews: List<TextView>
    private lateinit var errorViews: List<TextView>
    private lateinit var borderViews: List<View>
    private lateinit var numberViews: List<TextView>

    // ---------------- Ciclo de vida ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Cuatro streams en vivo sin interacción: sin esto la pantalla se apaga sola
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        kidsMode = KidsMode.isActive(this)
        formatIndex = prefs().getInt(KEY_FORMAT, 0).coerceIn(0, FORMATS.lastIndex)

        slotViews = listOf(binding.slot0, binding.slot1, binding.slot2, binding.slot3)
        playerViews = listOf(binding.player0, binding.player1, binding.player2, binding.player3)
        progressViews = listOf(binding.progress0, binding.progress1, binding.progress2, binding.progress3)
        labelViews = listOf(binding.label0, binding.label1, binding.label2, binding.label3)
        errorViews = listOf(binding.error0, binding.error1, binding.error2, binding.error3)
        borderViews = listOf(binding.border0, binding.border1, binding.border2, binding.border3)
        numberViews = listOf(binding.number0, binding.number1, binding.number2, binding.number3)

        // Mismo tratamiento que el botón Inicio del menú principal: degradado
        // pleno y el ícono teñido del color del texto.
        Appearance.applyLevel(binding.btnHome, Appearance.Level.PRIMARY, 22f)
        binding.btnHome.compoundDrawablesRelative.forEach {
            it?.mutate()?.setTint(binding.btnHome.currentTextColor)
        }
        binding.btnHome.setOnClickListener { goHome() }
        binding.btnReloadAll.setOnClickListener { reloadAll() }
        binding.btnSource.setOnClickListener { cycleFormat() }
        updateSourceLabel()

        for (i in 0 until SLOTS) {
            slotViews[i].setOnClickListener { setActiveSlot(i) }
            slotViews[i].setOnLongClickListener { showChannelPicker(i); true }
            labelViews[i].setText(R.string.multiscreen_slot_empty)
        }

        setActiveSlot(activeSlot)
        loadCategories()
    }

    /**
     * Los reproductores se crean acá y no en onCreate: al volver de segundo
     * plano onCreate no se ejecuta otra vez, y sin esto quedaban los cuatro
     * ExoPlayer liberados por onStop y la pantalla no volvía nunca de negro.
     */
    override fun onStart() {
        super.onStart()
        for (i in 0 until SLOTS) {
            if (players[i] != null) continue
            val exo = PlayerFactory.build(this, handleAudioFocus = false)
            val listener = slotListener(i)
            exo.addListener(listener)
            exo.volume = if (i == activeSlot) 1f else 0f
            playerViews[i].player = exo
            players[i] = exo
            listeners[i] = listener
            // Si ya había un canal asignado antes de irse a segundo plano, vuelve solo
            assigned[i]?.let { play(i, it) }
        }
    }

    override fun onStop() {
        super.onStop()
        for (i in 0 until SLOTS) {
            listeners[i]?.let { l -> players[i]?.removeListener(l) }
            playerViews[i].player = null
            players[i]?.release()
            players[i] = null
            listeners[i] = null
        }
    }

    override fun onDestroy() {
        picker?.dismiss()
        picker = null
        super.onDestroy()
    }

    /** Vuelve al inicio de la app en lugar de dejar la pila donde estaba. */
    private fun goHome() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------- Carga del catálogo (liviana) ----------------

    /**
     * Primero las categorías. Es una respuesta de pocos KB que hasta el panel
     * más cargado devuelve al instante, y permite pedir después una sola
     * categoría en vez del catálogo entero.
     */
    private fun loadCategories() {
        showAllLoading(true)

        Session.api(this).getLiveCategories(Session.username(this), Session.password(this))
            .enqueue(object : Callback<List<Category>> {
                override fun onResponse(call: Call<List<Category>>, response: Response<List<Category>>) {
                    if (isFinishing || isDestroyed) return
                    val todas = response.body().orEmpty()
                        .filter { !Parental.isCategoryLocked(this@MultiScreenActivity, it.categoryId) }
                        .let { lista ->
                            if (kidsMode) lista.filter { KidsFilter.isKidsCategory(it.categoryName) } else lista
                        }

                    if (todas.isEmpty()) {
                        // Sin categorías utilizables: última chance, lo que haya en memoria
                        fallbackToCatalog()
                        return
                    }
                    categories = todas
                    loadCategory(todas.first().categoryId)
                }

                override fun onFailure(call: Call<List<Category>>, t: Throwable) {
                    if (isFinishing || isDestroyed) return
                    fallbackToCatalog(t::class.java.simpleName)
                }
            })
    }

    private fun loadCategory(categoryId: String?, onLoaded: (() -> Unit)? = null) {
        currentCategoryId = categoryId
        Session.api(this).getLiveStreams(
            Session.username(this), Session.password(this), categoryId = categoryId
        ).enqueue(object : Callback<List<LiveStream>> {
            override fun onResponse(call: Call<List<LiveStream>>, response: Response<List<LiveStream>>) {
                if (isFinishing || isDestroyed) return
                // El perfil de niños ya filtró las categorías más arriba: todo lo
                // que llega acá viene de una categoría apta, no hace falta filtrar de nuevo.
                channels = response.body().orEmpty()
                    .map { it.toContentItem() }
                    .filter { it.name.isNotBlank() }

                if (channels.isEmpty()) {
                    showAllLoading(false)
                    showAllError(getString(R.string.multiscreen_no_channels))
                } else {
                    fillEmptySlots()
                }
                onLoaded?.invoke()
            }

            override fun onFailure(call: Call<List<LiveStream>>, t: Throwable) {
                if (isFinishing || isDestroyed) return
                showAllLoading(false)
                showAllError(getString(R.string.multiscreen_load_error, t::class.java.simpleName))
                onLoaded?.invoke()
            }
        })
    }

    /** Si el panel no responde, se usa lo que ya esté descargado en memoria. */
    private fun fallbackToCatalog(motivo: String? = null) {
        // En perfil de niños no se cae al catálogo completo: sin la lista de
        // categorías aptas no hay forma de saber qué es apto, y mostrar todo
        // sería peor que no mostrar nada.
        val enMemoria = if (kidsMode && categories.isEmpty()) emptyList() else Catalog.live.toList()
        if (enMemoria.isNotEmpty()) {
            val aptas = categories.map { it.categoryId }.toSet()
            channels = if (kidsMode) enMemoria.filter { it.categoryId in aptas } else enMemoria
            if (channels.isNotEmpty()) {
                fillEmptySlots()
                return
            }
        }
        showAllLoading(false)
        showAllError(
            if (motivo != null) getString(R.string.multiscreen_load_error, motivo)
            else getString(R.string.multiscreen_no_channels)
        )
    }

    /**
     * Carga los 4 recuadros de una. Los que el usuario ya eligió a mano no se
     * tocan: solo se llenan los que están vacíos.
     */
    private fun fillEmptySlots() {
        var siguiente = 0
        for (i in 0 until SLOTS) {
            if (assigned[i] != null) continue
            val canal = channels.getOrNull(siguiente++) ?: continue
            play(i, canal)
        }
        showAllLoading(false)
    }

    // ---------------- Reproducción ----------------

    private fun play(slot: Int, item: ContentItem) {
        assigned[slot] = item
        labelViews[slot].text = item.name
        errorViews[slot].visibility = View.GONE
        progressViews[slot].visibility = View.VISIBLE

        val url = Session.liveStreamUrl(this, item.id, FORMATS[formatIndex])
        players[slot]?.apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    private fun slotListener(slot: Int) = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (isFinishing || isDestroyed) return
            progressViews[slot].visibility =
                if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY) errorViews[slot].visibility = View.GONE
        }

        override fun onPlayerError(error: PlaybackException) {
            if (isFinishing || isDestroyed) return
            progressViews[slot].visibility = View.GONE
            errorViews[slot].text = explain(error)
            errorViews[slot].visibility = View.VISIBLE
        }
    }

    /**
     * Traduce el error a algo accionable. El caso importante es el 403/512: la
     * cuenta tiene un tope de conexiones simultáneas y acá se abren cuatro, así
     * que el panel rechaza las que sobran. Es la explicación más habitual de
     * "se ven una o dos y las demás quedan en negro".
     */
    private fun explain(error: PlaybackException): String {
        val causa = error.cause
        if (causa is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
            return when (causa.responseCode) {
                403, 401, 512 -> getString(R.string.multiscreen_err_limit)
                404 -> getString(R.string.multiscreen_err_notfound)
                else -> getString(R.string.multiscreen_load_error, "HTTP ${causa.responseCode}")
            }
        }
        if (causa is androidx.media3.common.ParserException ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        ) {
            return getString(R.string.multiscreen_err_format, FORMATS[formatIndex])
        }
        return getString(R.string.multiscreen_load_error, error.errorCodeName)
    }

    private fun setActiveSlot(slot: Int) {
        activeSlot = slot
        players.forEachIndexed { index, exo -> exo?.volume = if (index == slot) 1f else 0f }
        // El background del FrameLayout queda tapado por el PlayerView, así que
        // el resaltado va en una vista superpuesta y en la chapa del número.
        borderViews.forEachIndexed { index, v ->
            v.visibility = if (index == slot) View.VISIBLE else View.GONE
        }
        numberViews.forEachIndexed { index, v ->
            v.setBackgroundResource(
                if (index == slot) R.drawable.bg_slot_number_active else R.drawable.bg_slot_number
            )
        }
    }

    private fun reloadAll() {
        for (i in 0 until SLOTS) assigned[i]?.let { play(i, it) }
    }

    /** Alterna entre .m3u8 y .ts y recarga los cuatro recuadros con el formato nuevo. */
    private fun cycleFormat() {
        formatIndex = (formatIndex + 1) % FORMATS.size
        prefs().edit().putInt(KEY_FORMAT, formatIndex).apply()
        updateSourceLabel()
        Toast.makeText(
            this, getString(R.string.multiscreen_source_changed, FORMATS[formatIndex]), Toast.LENGTH_SHORT
        ).show()
        reloadAll()
    }

    private fun updateSourceLabel() {
        binding.btnSource.text = getString(R.string.multiscreen_source_label, FORMATS[formatIndex])
    }

    private fun showAllLoading(loading: Boolean) {
        progressViews.forEach { it.visibility = if (loading) View.VISIBLE else View.GONE }
    }

    private fun showAllError(mensaje: String) {
        for (i in 0 until SLOTS) {
            if (assigned[i] != null) continue
            errorViews[i].text = mensaje
            errorViews[i].visibility = View.VISIBLE
        }
    }

    // ---------------- Selector de canal con buscador ----------------

    /**
     * Reemplaza al AlertDialog.setItems() anterior, que construía un array con
     * todos los nombres del panel. En el Sistema XL eso son decenas de miles de
     * elementos y el diálogo se colgaba antes de aparecer.
     */
    private fun showChannelPicker(slot: Int) {
        picker?.dismiss()

        val vista = DialogChannelPickerBinding.inflate(layoutInflater)
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(vista.root)
        picker = dialog

        vista.tvPickerTitle.text = getString(R.string.multiscreen_pick_for, slot + 1)

        val adapter = ChannelPickerAdapter { item ->
            dialog.dismiss()
            play(slot, item)
            setActiveSlot(slot)
        }
        vista.recyclerPicker.layoutManager = LinearLayoutManager(this)
        vista.recyclerPicker.adapter = adapter

        fun render(filtro: String) {
            val q = filtro.trim()
            val lista = if (q.isBlank()) channels
            else channels.filter { it.name.contains(q, ignoreCase = true) }
            adapter.submit(lista)
            vista.tvPickerStatus.text = getString(R.string.multiscreen_pick_count, lista.size)
            vista.btnPickerClear.visibility = if (q.isBlank()) View.GONE else View.VISIBLE
        }

        vista.etPickerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
        })
        vista.btnPickerClear.setOnClickListener { vista.etPickerSearch.setText("") }
        vista.btnPickerClose.setOnClickListener { dialog.dismiss() }

        renderPickerCategories(vista) { render(vista.etPickerSearch.text?.toString().orEmpty()) }
        render("")

        dialog.setOnDismissListener { if (picker === dialog) picker = null }
        dialog.show()
    }

    /** Chips de categoría dentro del selector: cambian la lista sin bajar todo. */
    private fun renderPickerCategories(vista: DialogChannelPickerBinding, onChanged: () -> Unit) {
        vista.pickerCategoryContainer.removeAllViews()
        if (categories.isEmpty()) {
            vista.pickerCategoryScroll.visibility = View.GONE
            return
        }
        vista.pickerCategoryScroll.visibility = View.VISIBLE

        categories.forEach { cat ->
            val chip: TextView =
                ItemCategoryBinding.inflate(layoutInflater, vista.pickerCategoryContainer, false).root
            chip.text = cat.categoryName
            chip.tag = cat.categoryId
            Appearance.applyChipState(chip, cat.categoryId == currentCategoryId)
            chip.setOnClickListener {
                vista.pickerProgress.visibility = View.VISIBLE
                loadCategory(cat.categoryId) {
                    if (picker?.isShowing == true) {
                        vista.pickerProgress.visibility = View.GONE
                        for (i in 0 until vista.pickerCategoryContainer.childCount) {
                            val c = vista.pickerCategoryContainer.getChildAt(i) as? TextView ?: continue
                            Appearance.applyChipState(c, c.tag == currentCategoryId)
                        }
                        onChanged()
                    }
                }
            }
            vista.pickerCategoryContainer.addView(chip)
        }
    }

    // ---------------- Adapter del selector ----------------

    private inner class ChannelPickerAdapter(
        private val onPick: (ContentItem) -> Unit
    ) : RecyclerView.Adapter<ChannelPickerAdapter.VH>() {

        private var items: List<ContentItem> = emptyList()

        fun submit(nuevos: List<ContentItem>) {
            items = nuevos
            notifyDataSetChanged()
        }

        inner class VH(val v: ItemChannelBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemChannelBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.v.tvName.text = item.name
            holder.v.ivFavorite.visibility = View.GONE
            holder.v.ivLock.visibility = View.GONE
            if (item.icon.isNullOrBlank()) {
                holder.v.ivLogo.setImageDrawable(null)
            } else {
                Picasso.get().load(item.icon).into(holder.v.ivLogo)
            }
            holder.v.root.setOnClickListener { onPick(item) }
        }
    }
}
