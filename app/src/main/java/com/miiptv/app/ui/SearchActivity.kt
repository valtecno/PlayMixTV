package com.miiptv.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.miiptv.app.R
import com.miiptv.app.api.*
import com.miiptv.app.databinding.ActivitySearchBinding
import com.miiptv.app.databinding.ItemRecentQueryBinding
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.Catalog
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.util.History
import com.miiptv.app.util.RecentSearches
import com.miiptv.app.util.RemoteControl
import com.miiptv.app.util.Parental
import com.miiptv.app.util.PinDialog
import java.util.concurrent.Executors

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: ContentAdapter

    /** Filtro de tipo activo. null = todos. */
    private var typeFilter: ContentType? = null

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var pendingFilter: Runnable? = null
    private var stillLoading = true

    /** Referencia estable para poder darse de baja del catálogo al cerrar. */
    private val catalogListener: (Boolean) -> Unit = { loading ->
        if (!isFinishing && !isDestroyed) {
            stillLoading = loading
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            updateCatalogSummary()
            // Clave: re-filtrar a medida que llegan los datos. Antes, si escribías
            // antes de que terminara la carga, el resultado quedaba vacío para siempre.
            runFilter(immediate = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ContentAdapter(onClick = { item -> openItem(item) })
        // Igual que en el inicio: con control remoto hay que ver sobre qué
        // resultado está parado el foco.
        adapter.remoteMode = RemoteControl.isEnabled(this)
        // Fila con carátula y etiqueta de tipo: acá se mezclan canales,
        // películas y series, y la fila normal no mostraba ni una cosa ni otra.
        adapter.searchMode = true
        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.clipChildren = false
        binding.recyclerResults.adapter = adapter
        binding.recyclerResults.setHasFixedSize(true)

        setupFilterChips()

        binding.etQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = runFilter()
            override fun afterTextChanged(s: Editable?) {}
        })

        // La tecla "buscar" del teclado ahora cierra el teclado y filtra ya mismo
        binding.etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                runFilter(immediate = true)
                true
            } else false
        }

        binding.tvClearRecent.setOnClickListener {
            RecentSearches.clear(this)
            renderRecentSearches()
        }

        binding.progressBar.visibility = View.VISIBLE
        Catalog.ensureLoaded(this, onUpdate = catalogListener)
        renderRecentSearches()
        updateStatus(0)
    }

    // ---------------- Filtros por tipo ----------------

    private fun setupFilterChips() {
        binding.chipAll.setOnClickListener { selectType(null) }
        binding.chipLive.setOnClickListener { selectType(ContentType.LIVE) }
        binding.chipMovies.setOnClickListener { selectType(ContentType.MOVIE) }
        binding.chipSeries.setOnClickListener { selectType(ContentType.SERIES) }
        selectType(null)
    }

    private fun selectType(type: ContentType?) {
        typeFilter = type
        val chips = mapOf<TextView, ContentType?>(
            binding.chipAll to null,
            binding.chipLive to ContentType.LIVE,
            binding.chipMovies to ContentType.MOVIE,
            binding.chipSeries to ContentType.SERIES
        )
        chips.forEach { (chip, chipType) ->
            // Mismo criterio activo/inactivo que en el resto de la app
            Appearance.applyChipState(chip, chipType == type)
        }
        runFilter(immediate = true)
    }

    // ---------------- Búsquedas recientes ----------------

    private fun renderRecentSearches() {
        val recents = RecentSearches.getAll(this)
        binding.recentContainer.removeAllViews()
        binding.recentSection.visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE
        recents.forEach { q ->
            val chip = ItemRecentQueryBinding.inflate(layoutInflater, binding.recentContainer, false).root
            chip.text = q
            chip.setOnClickListener {
                binding.etQuery.setText(q)
                binding.etQuery.setSelection(q.length)
                runFilter(immediate = true)
            }
            chip.setOnLongClickListener {
                RecentSearches.remove(this, q)
                renderRecentSearches()
                true
            }
            binding.recentContainer.addView(chip)
        }
    }

    private fun updateCatalogSummary() {
        binding.tvCatalogSummary.text = getString(
            R.string.catalog_summary, Catalog.movies.size, Catalog.series.size, Catalog.live.size
        )
        // Si un bloque no llegó, decirlo. Antes se veía "0 películas" sin ninguna
        // explicación y parecía que el panel no tenía nada.
        val motivo = Catalog.lastError
        if (motivo != null && !Catalog.isLoading) {
            binding.tvCatalogSummary.append("\n" + getString(R.string.catalog_error, motivo))
        }
    }

    // ---------------- Filtrado ----------------

    /**
     * Filtra con un pequeño retraso (debounce) y fuera del hilo principal.
     * Recorrer decenas de miles de títulos en cada tecla congelaba la pantalla.
     */
    private fun runFilter(immediate: Boolean = false) {
        pendingFilter?.let { ui.removeCallbacks(it) }
        val task = Runnable { doFilter() }
        pendingFilter = task
        ui.postDelayed(task, if (immediate) 0L else 250L)
    }

    private fun doFilter() {
        val query = binding.etQuery.text?.toString()?.trim().orEmpty()
        val type = typeFilter

        worker.execute {
            val source = when (type) {
                ContentType.LIVE -> Catalog.live
                ContentType.MOVIE -> Catalog.movies
                ContentType.SERIES -> Catalog.series
                null -> Catalog.all()
            }.toList() // copia: la lista original puede seguir creciendo mientras carga

            val results = if (query.length < 2) {
                emptyList()
            } else {
                source.filter { it.name.contains(query, ignoreCase = true) }
                    .sortedBy { it.name.indexOf(query, ignoreCase = true) } // coincidencias al inicio primero
                    .take(300)
            }

            ui.post {
                if (isFinishing || isDestroyed) return@post
                // Con el remoto, antes de repintar hay que anotar en qué fila
                // estaba el foco: submitList() vuelve a crear las vistas y esa
                // fila deja de existir. Sin esto, cada refiltrado (al tipear o
                // cuando el catálogo sigue cargando en segundo plano) le hacía
                // perder el foco a Android y el mando parecía muerto.
                val remoto = RemoteControl.isEnabled(this@SearchActivity)
                val posicionEnfocada = if (remoto) {
                    binding.recyclerResults.focusedChild
                        ?.let { binding.recyclerResults.getChildAdapterPosition(it) }
                        ?.takeIf { it != androidx.recyclerview.widget.RecyclerView.NO_POSITION }
                } else null

                adapter.submitList(results)
                if (posicionEnfocada == null) binding.recyclerResults.scrollToPosition(0)
                updateStatus(results.size, query)
                binding.recentSection.visibility =
                    if (query.length < 2 && RecentSearches.getAll(this@SearchActivity).isNotEmpty())
                        View.VISIBLE else View.GONE

                if (remoto && results.isNotEmpty()) {
                    // Se repone el foco en la misma fila de antes si todavía
                    // existe (la lista pudo achicarse), o si no en la primera.
                    val destino = posicionEnfocada?.coerceIn(0, results.size - 1) ?: 0
                    binding.recyclerResults.post {
                        binding.recyclerResults.layoutManager
                            ?.findViewByPosition(destino)
                            ?.requestFocus()
                    }
                }
            }
        }
    }

    private fun updateStatus(count: Int, query: String = "") {
        binding.tvStatus.text = when {
            query.length < 2 && stillLoading -> getString(R.string.search_loading_catalog)
            query.length < 2 -> getString(R.string.search_type_to_start)
            count == 0 && stillLoading -> getString(R.string.search_loading_catalog)
            count == 0 -> getString(R.string.search_no_results, query)
            else -> resources.getQuantityString(R.plurals.search_results, count, count)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etQuery.windowToken, 0)
    }

    // ---------------- Abrir contenido ----------------

    private fun openItem(item: ContentItem) {
        if (Parental.isCategoryLocked(this, item.categoryId)) {
            PinDialog.ask(this) { reallyOpen(item) }
        } else {
            reallyOpen(item)
        }
    }

    private fun reallyOpen(item: ContentItem) {
        RecentSearches.add(this, binding.etQuery.text?.toString().orEmpty())
        val abreFicha = item.type == ContentType.MOVIE &&
            Appearance.getMovieClick(this) == Appearance.CLICK_DETAILS
        if (!abreFicha) History.add(this, item)
        when (item.type) {
            ContentType.LIVE -> startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_URL, Session.liveStreamUrl(this, item.id))
                    .putExtra(PlayerActivity.EXTRA_TITLE, item.name)
                    .putExtra(PlayerActivity.EXTRA_ITEM_ID, item.id)
                    .putExtra(PlayerActivity.EXTRA_ITEM_ICON, item.icon)
                    .putExtra(PlayerActivity.EXTRA_ITEM_CATEGORY, item.categoryId)
                    .putExtra(PlayerActivity.EXTRA_ITEM_TYPE, item.type.name)
                    .putExtra(PlayerActivity.EXTRA_ITEM_EXT, item.containerExtension)
            )
            ContentType.MOVIE -> if (abreFicha) {
                startActivity(
                    Intent(this, MovieDetailActivity::class.java)
                        .putExtra(MovieDetailActivity.EXTRA_ID, item.id)
                        .putExtra(MovieDetailActivity.EXTRA_NAME, item.name)
                        .putExtra(MovieDetailActivity.EXTRA_ICON, item.icon)
                        .putExtra(MovieDetailActivity.EXTRA_EXT, item.containerExtension)
                        .putExtra(MovieDetailActivity.EXTRA_CATEGORY, item.categoryId)
                )
            } else {
                startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .putExtra(
                            PlayerActivity.EXTRA_URL,
                            Session.vodStreamUrl(this, item.id, item.containerExtension ?: "mp4")
                        )
                        .putExtra(PlayerActivity.EXTRA_TITLE, item.name)
                    .putExtra(PlayerActivity.EXTRA_ITEM_ID, item.id)
                    .putExtra(PlayerActivity.EXTRA_ITEM_ICON, item.icon)
                    .putExtra(PlayerActivity.EXTRA_ITEM_CATEGORY, item.categoryId)
                    .putExtra(PlayerActivity.EXTRA_ITEM_TYPE, item.type.name)
                    .putExtra(PlayerActivity.EXTRA_ITEM_EXT, item.containerExtension)
                )
            }
            ContentType.SERIES -> startActivity(
                Intent(this, SeriesDetailActivity::class.java)
                    .putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.id)
                    .putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.name)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (binding.etQuery.text.isNullOrBlank()) renderRecentSearches()
    }

    override fun onDestroy() {
        // Evita que un resultado tardío intente pintar sobre una pantalla ya cerrada
        Catalog.removeListener(catalogListener)
        pendingFilter?.let { ui.removeCallbacks(it) }
        worker.shutdownNow()
        super.onDestroy()
    }
}
