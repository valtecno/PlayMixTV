package com.miiptv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.miiptv.app.R
import com.miiptv.app.api.Category
import com.miiptv.app.api.ContentItem
import com.miiptv.app.api.ContentType
import com.miiptv.app.api.Session
import com.miiptv.app.databinding.ActivityGuideBinding
import com.miiptv.app.databinding.ItemCategoryBinding
import com.miiptv.app.databinding.ItemGuideRowBinding
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.Catalog
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.util.Epg
import com.miiptv.app.util.Favorites
import com.miiptv.app.util.Parental
import com.miiptv.app.util.RemoteControl
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Guía de programación: una fila por canal con sus programas como bloques
 * sobre una regla de 3 horas que arranca en la media hora anterior a "ahora".
 *
 * Los canales salen del catálogo ya cargado ([Catalog.live]); la programación
 * se pide por fila, solo para las filas que se ven, con la caché de [Epg.guide].
 * Tocar una fila abre el canal en el reproductor.
 */
class GuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuideBinding
    private val adapter = GuideAdapter { abrir(it) }

    private var inicioSeg = 0L
    private var finSeg = 0L
    private var categoriaActual: String = FAVORITOS
    private var categorias: List<Category> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = getString(R.string.guide_title)

        // Ventana: desde la media hora anterior a ahora, 3 horas.
        val ahora = System.currentTimeMillis() / 1000
        inicioSeg = ahora / 1800 * 1800
        finSeg = inicioSeg + 3 * 3600
        binding.guideTimeBar.setVentana(inicioSeg, finSeg)

        binding.recyclerGuide.layoutManager = LinearLayoutManager(this)
        binding.recyclerGuide.adapter = adapter

        if (Catalog.live.isNotEmpty() && Catalog.isFor(this)) {
            cargarCategorias()
        } else {
            binding.guideProgress.visibility = View.VISIBLE
            Catalog.ensureLoaded(this) { _ ->
                if (isFinishing || isDestroyed) return@ensureLoaded
                if (Catalog.live.isNotEmpty() && binding.guideChips.childCount == 0) cargarCategorias()
                else if (!Catalog.isLoading && Catalog.live.isEmpty()) {
                    binding.guideProgress.visibility = View.GONE
                    mostrarVacio(getString(R.string.guide_empty))
                }
            }
        }
    }

    private fun cargarCategorias() {
        Session.api(this).getLiveCategories(Session.username(this), Session.password(this))
            .enqueue(object : Callback<List<Category>> {
                override fun onResponse(call: Call<List<Category>>, response: Response<List<Category>>) {
                    if (isFinishing || isDestroyed) return
                    categorias = response.body().orEmpty()
                        .filter { !Parental.isCategoryLocked(this@GuideActivity, it.categoryId) }
                    armarChips()
                }

                override fun onFailure(call: Call<List<Category>>, t: Throwable) {
                    if (isFinishing || isDestroyed) return
                    categorias = emptyList()
                    armarChips()
                }
            })
    }

    private fun armarChips() {
        binding.guideChips.removeAllViews()
        fun chip(texto: String, id: String) {
            val c: TextView = ItemCategoryBinding.inflate(layoutInflater, binding.guideChips, false).root
            c.text = texto
            c.tag = id
            Appearance.applyChipState(c, id == categoriaActual)
            c.setOnClickListener { elegir(id) }
            RemoteControl.applyFocusToTree(c, RemoteControl.isEnabled(this))
            binding.guideChips.addView(c)
        }
        chip(getString(R.string.guide_favorites), FAVORITOS)
        categorias.forEach { chip(it.categoryName, it.categoryId) }

        // Sin favoritos de TV en vivo se abre directo la primera categoría.
        if (categoriaActual == FAVORITOS && canalesDe(FAVORITOS).isEmpty() && categorias.isNotEmpty()) {
            categoriaActual = categorias.first().categoryId
            for (i in 0 until binding.guideChips.childCount) {
                val c = binding.guideChips.getChildAt(i) as TextView
                Appearance.applyChipState(c, c.tag == categoriaActual)
            }
        }
        mostrar()
    }

    private fun elegir(id: String) {
        categoriaActual = id
        for (i in 0 until binding.guideChips.childCount) {
            val c = binding.guideChips.getChildAt(i) as TextView
            Appearance.applyChipState(c, c.tag == id)
        }
        mostrar()
    }

    private fun canalesDe(id: String): List<ContentItem> =
        if (id == FAVORITOS) {
            Favorites.getAll(this).filter { it.type == ContentType.LIVE && it.streamUrl.isNullOrBlank() }
        } else {
            Catalog.live.filter { it.categoryId == id }
        }

    private fun mostrar() {
        binding.guideProgress.visibility = View.GONE
        val lista = canalesDe(categoriaActual)
        adapter.submit(lista)
        binding.recyclerGuide.scrollToPosition(0)
        if (lista.isEmpty()) {
            mostrarVacio(
                getString(if (categoriaActual == FAVORITOS) R.string.guide_no_favorites else R.string.guide_empty)
            )
        } else {
            binding.tvGuideEmpty.visibility = View.GONE
        }
    }

    private fun mostrarVacio(texto: String) {
        binding.tvGuideEmpty.text = texto
        binding.tvGuideEmpty.visibility = View.VISIBLE
    }

    private fun abrir(item: ContentItem) {
        val abrirCanal = {
            val lista = adapter.items
            val actual = lista.indexOfFirst { it.id == item.id }
            val intent = Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, Session.liveStreamUrl(this, item.id))
                .putExtra(PlayerActivity.EXTRA_TITLE, item.name)
                .putExtra(PlayerActivity.EXTRA_ITEM_ID, item.id)
                .putExtra(PlayerActivity.EXTRA_ITEM_ICON, item.icon)
                .putExtra(PlayerActivity.EXTRA_ITEM_CATEGORY, item.categoryId)
                .putExtra(PlayerActivity.EXTRA_ITEM_TYPE, item.type.name)
                .putExtra(PlayerActivity.EXTRA_ITEM_EXT, item.containerExtension)
                .putExtra(PlayerActivity.EXTRA_EPG_ALLOWED, true)
            if (actual >= 0 && lista.size > 1) {
                val desde = (actual - 200).coerceAtLeast(0)
                val hasta = (actual + 201).coerceAtMost(lista.size)
                val ventana = lista.subList(desde, hasta)
                intent
                    .putIntegerArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_IDS, ArrayList(ventana.map { it.id }))
                    .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_TITLES, ArrayList(ventana.map { it.name }))
                    .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, actual - desde)
            }
            startActivity(intent)
        }
        abrirCanal()
    }

    // ---------------- Adapter ----------------

    private inner class GuideAdapter(
        private val onClick: (ContentItem) -> Unit
    ) : RecyclerView.Adapter<GuideAdapter.VH>() {

        var items: List<ContentItem> = emptyList()
            private set

        fun submit(nuevos: List<ContentItem>) {
            items = nuevos
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemGuideRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemGuideRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.b.tvGuideChannel.text = item.name
            holder.b.guideRow.setVentana(inicioSeg, finSeg)
            holder.b.guideRow.setProgramas(null)
            holder.b.guideRow.tag = item.id
            holder.b.root.setOnClickListener { onClick(item) }
            RemoteControl.applyItemFocus(holder.b.root, RemoteControl.isEnabled(this@GuideActivity))

            Epg.guide(this@GuideActivity, item.id) { listados ->
                // La fila pudo reciclarse para otro canal mientras llegaba la respuesta
                if (holder.b.guideRow.tag == item.id) holder.b.guideRow.setProgramas(listados)
            }
        }

        override fun getItemCount() = items.size
    }

    private companion object {
        const val FAVORITOS = "__favoritos__"
    }
}
