package com.miiptv.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.miiptv.app.R
import com.miiptv.app.api.*
import com.miiptv.app.databinding.ActivityMainBinding
import com.miiptv.app.databinding.ItemCategoryBinding
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.Catalog
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.util.Favorites
import com.miiptv.app.util.History
import com.miiptv.app.util.KidsFilter
import com.miiptv.app.util.KidsMode
import com.miiptv.app.util.Parental
import com.miiptv.app.util.PpvFilter
import com.miiptv.app.util.RadioCatalog
import com.miiptv.app.util.PinDialog
import com.miiptv.app.util.applyBrandGradient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : AppCompatActivity() {

    /** Secciones de la barra de navegación superior. */
    private enum class Section { HOME, LIVE, PPV, RADIO, MOVIES, SERIES, HISTORY, FAVORITES }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ContentAdapter
    private lateinit var carouselAdapter: CarouselAdapter
    private lateinit var recentAdapter: CarouselAdapter
    private lateinit var novedades: AutoCarousel
    private lateinit var recientes: AutoCarousel

    private var section: Section = Section.HOME
    private var currentCategoryId: String? = null
    /** Tipo elegido dentro de Favoritos: null = todos. */
    private var favFilter: ContentType? = null
    private var favIsRadio = false
    /** Categorías de PPV sin filtrar, para el buscador propio de esa sección. */
    private var ppvCategories: List<Category> = emptyList()
    /** País de radios abierto ahora mismo. */
    private var currentRadioCode: String? = null
    /** Última lista cargada, sin filtrar: base del buscador del perfil de niños. */
    private var currentItems: List<ContentItem> = emptyList()
    private var categories: List<Category> = emptyList()
    /** Perfil de niños: filtra a solo contenido infantil y oculta las secciones no aptas. */
    private var kidsMode: Boolean = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        // El Toolbar dibujaba su título nativo (negro, ilegible) detrás del logo + texto
        supportActionBar?.setDisplayShowTitleEnabled(false)

        if (!Session.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        adapter = ContentAdapter(onClick = { item -> openItem(item) })
        binding.recyclerChannels.layoutManager = GridLayoutManager(this, 1)
        binding.recyclerChannels.adapter = adapter

        binding.tvToolbarTitle.applyBrandGradient()

        kidsMode = KidsMode.isActive(this)

        setupNav()
        setupPpvSearch()
        setupKidsSearch()
        setupCarousel()
        applyKidsVisibility()

        selectSection(if (kidsMode) Section.LIVE else Section.HOME)
    }

    // ---------------- Barra de navegación ----------------

    private fun setupNav() {
        binding.navHome.setOnClickListener { selectSection(Section.HOME) }
        binding.navLive.setOnClickListener { selectSection(Section.LIVE) }
        binding.navPpv.setOnClickListener { selectSection(Section.PPV) }
        binding.navRadio.setOnClickListener { selectSection(Section.RADIO) }
        binding.navMovies.setOnClickListener { selectSection(Section.MOVIES) }
        binding.navSeries.setOnClickListener { selectSection(Section.SERIES) }
        binding.navFavorites.setOnClickListener { selectSection(Section.FAVORITES) }

        binding.navKids.setOnClickListener { toggleKidsMode() }
    }

    private fun highlightNav() {
        val map = mapOf<TextView, Section?>(
            binding.navHome to Section.HOME,
            binding.navLive to Section.LIVE,
            binding.navPpv to Section.PPV,
            binding.navRadio to Section.RADIO,
            binding.navMovies to Section.MOVIES,
            binding.navSeries to Section.SERIES,
            binding.navFavorites to Section.FAVORITES
        )
        map.forEach { (view, sec) ->
            val active = sec != null && sec == section
            paintNavItem(view, active)
        }
        // El de Niños no representa una Section: se resalta según kidsMode y cambia
        // de texto/ícono para indicar que, tocándolo de nuevo, se pide el PIN de salida.
        binding.navKids.text = getString(if (kidsMode) R.string.nav_kids_exit else R.string.nav_kids)
        paintNavItem(binding.navKids, kidsMode)
        // El perfil de niños se distingue en verde, activo o no
        val verdeNinos = ContextCompat.getColor(
            this, if (kidsMode) R.color.kids_green else R.color.kids_green_dim
        )
        binding.navKids.setTextColor(verdeNinos)
        binding.navKids.compoundDrawablesRelative.forEach { it?.mutate()?.setTint(verdeNinos) }
    }

    /** Pinta un ítem de la barra: degradado de marca si está activo, fondo oscuro si no. */
    private fun paintNavItem(view: TextView, active: Boolean) {
        // Nivel 1: la sección abierta del menú principal, con degradado pleno
        Appearance.applyLevel(
            view,
            if (active) Appearance.Level.PRIMARY else Appearance.Level.INACTIVE,
            22f
        )
        val color = view.currentTextColor
        // mutate() evita teñir la copia compartida del drawable en otras vistas
        view.compoundDrawablesRelative.forEach { it?.mutate()?.setTint(color) }
    }

    private fun selectSection(newSection: Section) {
        section = newSection
        highlightNav()
        binding.tvEmpty.visibility = View.GONE
        binding.tvSectionTitle.visibility = View.GONE

        val isHome = newSection == Section.HOME
        val isBrowse = newSection in listOf(
            Section.LIVE, Section.PPV, Section.RADIO, Section.MOVIES, Section.SERIES
        )

        binding.homeSections.visibility = if (isHome) View.VISIBLE else View.GONE
        binding.recyclerChannels.visibility = if (isHome) View.GONE else View.VISIBLE
        binding.categoryScroll.visibility = if (isBrowse) View.VISIBLE else View.GONE
        binding.favFilterScroll.visibility =
            if (newSection == Section.FAVORITES) View.VISIBLE else View.GONE
        if (newSection != Section.PPV) binding.etPpvSearch.visibility = View.GONE
        binding.etKidsSearch.visibility =
            if (kidsMode && newSection in listOf(Section.LIVE, Section.MOVIES, Section.SERIES))
                View.VISIBLE else View.GONE

        applyLayoutMode(newSection)
        if (isHome) startCarousel() else stopCarousel()

        when (newSection) {
            Section.HOME -> showHome()
            Section.LIVE -> loadCategories(ContentType.LIVE, kidsFilterOrNull())
            Section.PPV -> showPpv()
            Section.RADIO -> showRadio()
            Section.MOVIES -> loadCategories(ContentType.MOVIE, kidsFilterOrNull())
            Section.SERIES -> loadCategories(ContentType.SERIES, kidsFilterOrNull())
            Section.HISTORY -> showHistory()
            Section.FAVORITES -> showFavorites()
        }
    }

    /** Filtro adicional de categorías cuando el Perfil de niños está activo. */
    private fun kidsFilterOrNull(): ((Category) -> Boolean)? =
        if (kidsMode) { c -> KidsFilter.isKidsCategory(c.categoryName) } else null

    /**
     * Películas y Series se muestran como grilla de pósters, con las columnas
     * configuradas en Personalizar. El resto va como lista de filas.
     */
    private fun applyLayoutMode(newSection: Section) {
        val columns = when (newSection) {
            Section.MOVIES -> Appearance.getMoviesColumns(this)
            Section.SERIES -> Appearance.getSeriesColumns(this)
            else -> 1
        }
        adapter.posterMode = columns > 1
        binding.recyclerChannels.layoutManager = GridLayoutManager(this, columns)
    }

    /** Tipo de contenido de la sección actual (para las pantallas de catálogo). */
    private fun currentType(): ContentType = when (section) {
        Section.MOVIES -> ContentType.MOVIE
        Section.SERIES -> ContentType.SERIES
        else -> ContentType.LIVE
    }

    // ---------------- Inicio ----------------

    private fun showHome() {
        binding.categoryContainer.removeAllViews()
        setLoading(false)
        adapter.submitList(emptyList())

        // Fila 1: lo más nuevo. Fila 2: lo que sigue, sin repetir nada de la primera.
        val todo = Catalog.newest(this, limit = 40)
        val fila1 = todo.take(20)
        val fila2 = todo.drop(20)

        novedades.submit(fila1)
        recientes.submit(fila2)

        binding.colNovedades.visibility = if (novedades.itemCount == 0) View.GONE else View.VISIBLE
        binding.colRecientes.visibility = if (recientes.itemCount == 0) View.GONE else View.VISIBLE

        val vacio = novedades.itemCount == 0 && recientes.itemCount == 0
        binding.tvEmpty.setText(R.string.empty_list)
        binding.tvEmpty.visibility = if (vacio && !Catalog.isEmpty) View.VISIBLE else View.GONE

        startCarousel()
    }

    // ---------------- Carrusel de novedades ----------------

    private val catalogListener: (Boolean) -> Unit = { _ ->
        if (!isFinishing && !isDestroyed && section == Section.HOME) {
            showHome()
        }
    }

    /**
     * En móvil las dos secciones del inicio van apiladas (Novedades arriba,
     * Agregados debajo); en TV van lado a lado, aprovechando el ancho.
     */
    private fun applyHomeOrientation() {
        val movil = DeviceMode.isMobile(this)
        binding.homeSections.orientation =
            if (movil) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        listOf(binding.colNovedades, binding.colRecientes).forEach { columna ->
            val lp = columna.layoutParams as LinearLayout.LayoutParams
            if (movil) {
                lp.width = LinearLayout.LayoutParams.MATCH_PARENT
                lp.height = 0
            } else {
                lp.width = 0
                lp.height = LinearLayout.LayoutParams.MATCH_PARENT
            }
            lp.weight = 1f
            columna.layoutParams = lp
        }
    }

    private fun setupCarousel() {
        applyHomeOrientation()
        carouselAdapter = CarouselAdapter(onClick = { item -> openItem(item) })
        recentAdapter = CarouselAdapter(onClick = { item -> openItem(item) })

        // Un póster por vez en cada columna, avanzando solo
        novedades = AutoCarousel(binding.recyclerCarousel, carouselAdapter, perPage = 1, delayMs = 5000L)
        recientes = AutoCarousel(binding.recyclerRecent, recentAdapter, perPage = 1, delayMs = 5000L)
        novedades.attach()
        recientes.attach()

        // El catálogo se carga una sola vez y lo reutiliza también el buscador
        Catalog.ensureLoaded(this, onUpdate = catalogListener)
    }

    private fun startCarousel() {
        if (!::novedades.isInitialized) return
        novedades.start()
        // Medio ciclo de desfase: así las dos columnas no cambian a la vez
        recientes.start(startOffsetMs = 2500L)
    }

    private fun stopCarousel() {
        // Puede llamarse desde onDestroy aunque onCreate haya salido antes de inicializarlos
        if (!::novedades.isInitialized) return
        novedades.stop()
        recientes.stop()
    }

    // ---------------- Menú superior ----------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    /** Con el Perfil de niños activo, Personalizar y Cuenta quedan ocultos. */
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Dentro del perfil de niños solo queda "actualizar": ni búsqueda global,
        // ni control parental, ni multi, ni cuenta.
        menu.findItem(R.id.action_multi)?.isVisible = !kidsMode
        menu.findItem(R.id.action_search)?.isVisible = !kidsMode
        menu.findItem(R.id.action_parental)?.isVisible = !kidsMode
        menu.findItem(R.id.action_account)?.isVisible = !kidsMode
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> refreshAll()
            R.id.action_search -> startActivity(Intent(this, SearchActivity::class.java))
            R.id.action_parental -> openParentalSettings()
            R.id.action_multi -> startActivity(Intent(this, MultiScreenActivity::class.java))
            R.id.action_account -> startActivity(Intent(this, SettingsActivity::class.java))
        }
        return true
    }

    // ---------------- Perfil de niños ----------------

    /** Toca el ítem "Niños": entra al perfil, o si ya está activo, pide el PIN para salir. */
    private fun toggleKidsMode() {
        if (kidsMode) {
            PinDialog.ask(this) { exitKidsMode() }
        } else {
            enterKidsMode()
        }
    }

    private fun enterKidsMode() {
        if (!Parental.hasPin(this)) {
            Toast.makeText(this, R.string.kids_mode_need_pin, Toast.LENGTH_LONG).show()
            PinDialog.create(this) { activateKidsMode() }
        } else {
            activateKidsMode()
        }
    }

    private fun activateKidsMode() {
        kidsMode = true
        KidsMode.setActive(this, true)
        applyKidsVisibility()
        Toast.makeText(this, R.string.kids_mode_on, Toast.LENGTH_LONG).show()
        selectSection(Section.LIVE)
    }

    private fun exitKidsMode() {
        kidsMode = false
        KidsMode.setActive(this, false)
        applyKidsVisibility()
        Toast.makeText(this, R.string.kids_mode_off, Toast.LENGTH_SHORT).show()
        selectSection(Section.HOME)
    }

    /** Oculta lo que no es apto (PPV, Radios, Historial, Favoritos, Inicio) mientras dura el perfil. */
    private fun applyKidsVisibility() {
        val visibility = if (kidsMode) View.GONE else View.VISIBLE
        binding.navHome.visibility = visibility
        binding.navPpv.visibility = visibility
        binding.navRadio.visibility = visibility
        binding.navFavorites.visibility = visibility
        invalidateOptionsMenu()
        if (::adapter.isInitialized) highlightNav()
    }

    private fun refreshAll() {
        Toast.makeText(this, R.string.catalog_refreshing, Toast.LENGTH_SHORT).show()
        Catalog.ensureLoaded(this, force = true, onUpdate = catalogListener)
        selectSection(section)
    }

    private fun openParentalSettings() {
        if (Parental.hasPin(this)) {
            PinDialog.ask(this) {
                startActivity(Intent(this, ParentalSettingsActivity::class.java))
            }
        } else {
            startActivity(Intent(this, ParentalSettingsActivity::class.java))
        }
    }

    // ---------------- Catálogo por categorías ----------------

    private fun loadCategories(type: ContentType, filter: ((Category) -> Boolean)? = null) {
        setLoading(true)
        val call = when (type) {
            ContentType.LIVE -> Session.api(this).getLiveCategories(Session.username(this), Session.password(this))
            ContentType.MOVIE -> Session.api(this).getVodCategories(Session.username(this), Session.password(this))
            ContentType.SERIES -> Session.api(this).getSeriesCategories(Session.username(this), Session.password(this))
        }
        call.enqueue(object : Callback<List<Category>> {
            override fun onResponse(call: Call<List<Category>>, response: Response<List<Category>>) {
                if (isFinishing || isDestroyed) return
                categories = response.body().orEmpty().let { list ->
                    if (filter != null) list.filter(filter) else list
                }
                if (section == Section.PPV) ppvCategories = categories
                renderCategoryChips(categories)
                if (categories.isEmpty()) {
                    setLoading(false)
                    adapter.submitList(emptyList())
                    binding.tvEmpty.setText(
                        when {
                            kidsMode -> R.string.empty_kids
                            section == Section.PPV -> R.string.empty_ppv
                            else -> R.string.empty_list
                        }
                    )
                    binding.tvEmpty.visibility = View.VISIBLE
                } else {
                    loadContent(type, categories.first().categoryId)
                }
            }

            override fun onFailure(call: Call<List<Category>>, t: Throwable) {
                if (isFinishing) return
                setLoading(false)
                Toast.makeText(this@MainActivity, "Error cargando categorías: ${t.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun renderCategoryChips(categories: List<Category>) {
        binding.categoryContainer.removeAllViews()
        categories.forEach { cat ->
            val chip: TextView = ItemCategoryBinding.inflate(layoutInflater, binding.categoryContainer, false).root
            chip.text = cat.categoryName + if (Parental.isCategoryLocked(this, cat.categoryId)) " 🔒" else ""
            chip.tag = cat.categoryId
            // Antes todas se pintaban iguales: no se veía cuál estaba abierta
            Appearance.applyChipState(chip, cat.categoryId == currentCategoryId)
            chip.setOnClickListener {
                if (Parental.isCategoryLocked(this, cat.categoryId)) {
                    PinDialog.ask(this) { loadContent(currentType(), cat.categoryId) }
                } else {
                    loadContent(currentType(), cat.categoryId)
                }
            }
            binding.categoryContainer.addView(chip)
        }
    }

    /** Repinta los chips para reflejar cuál está abierto ahora. */
    private fun refreshCategorySelection() {
        for (i in 0 until binding.categoryContainer.childCount) {
            val chip = binding.categoryContainer.getChildAt(i) as? TextView ?: continue
            Appearance.applyChipState(chip, chip.tag == currentCategoryId)
        }
    }

    private fun loadContent(type: ContentType, categoryId: String?) {
        currentCategoryId = categoryId
        refreshCategorySelection()
        setLoading(true)
        val user = Session.username(this)
        val pass = Session.password(this)
        when (type) {
            ContentType.LIVE -> Session.api(this).getLiveStreams(user, pass, categoryId = categoryId)
                .enqueue(simpleCallback { list -> list.map { it.toContentItem() } })
            ContentType.MOVIE -> Session.api(this).getVodStreams(user, pass, categoryId = categoryId)
                .enqueue(simpleCallback { list -> list.map { it.toContentItem() } })
            ContentType.SERIES -> Session.api(this).getSeries(user, pass, categoryId = categoryId)
                .enqueue(simpleCallback { list -> list.map { it.toContentItem() } })
        }
    }

    private fun <T> simpleCallback(map: (List<T>) -> List<ContentItem>) = object : Callback<List<T>> {
        override fun onResponse(call: Call<List<T>>, response: Response<List<T>>) {
            if (isFinishing || isDestroyed) return
            setLoading(false)
            val items = map(response.body().orEmpty()).filter { it.name.isNotBlank() }
            currentItems = items
            adapter.submitList(items)
            // Si el perfil de niños tiene una búsqueda escrita, se respeta
            if (kidsMode) {
                val q = binding.etKidsSearch.text?.toString().orEmpty()
                if (q.isNotBlank()) { applyKidsSearch(q); return }
            }
            binding.tvEmpty.setText(R.string.empty_list)
            binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }

        override fun onFailure(call: Call<List<T>>, t: Throwable) {
            if (isFinishing) return
            setLoading(false)
            Toast.makeText(this@MainActivity, "Error cargando contenido: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- PPV Fútbol VIP ----------------

    /** Solo las categorías de fútbol del servidor (PPV y eventos); el resto se descarta. */
    /**
     * PPV: muestra directamente las carpetas de fútbol del servidor, sin rótulo,
     * y con un buscador propio para filtrarlas por nombre.
     */
    private fun showPpv() {
        binding.tvSectionTitle.visibility = View.GONE
        binding.etPpvSearch.visibility = View.VISIBLE
        binding.etPpvSearch.setText("")
        // El buscador necesita el catálogo en memoria para buscar por equipo o país
        Catalog.ensureLoaded(this) { }
        loadCategories(ContentType.LIVE) { PpvFilter.isFootball(it.categoryName) }
    }

    /** Buscador propio del perfil de niños: filtra lo que ya está en pantalla. */
    private fun setupKidsSearch() {
        binding.etKidsSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                applyKidsSearch(s?.toString().orEmpty())
            }
        })
    }

    private fun applyKidsSearch(query: String) {
        val q = query.trim()
        val visibles = if (q.isBlank()) currentItems
        else currentItems.filter { it.name.contains(q, ignoreCase = true) }
        adapter.submitList(visibles)
        binding.tvEmpty.setText(if (q.isBlank()) R.string.empty_kids else R.string.kids_no_match)
        binding.tvEmpty.visibility = if (visibles.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupPpvSearch() {
        binding.etPpvSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                filterPpvCategories(s?.toString().orEmpty())
            }
        })
    }

    /**
     * Busca en **todo** el contenido PPV, no solo en los nombres de carpeta:
     * recorre los canales de todas las categorías de fútbol, así se encuentra
     * por equipo, país, liga o nombre del evento.
     */
    private fun filterPpvCategories(query: String) {
        val q = query.trim()

        if (q.isBlank()) {
            // Sin búsqueda: vuelve la navegación normal por carpetas
            categories = ppvCategories
            renderCategoryChips(ppvCategories)
            if (ppvCategories.isEmpty()) {
                adapter.submitList(emptyList())
                binding.tvEmpty.setText(R.string.empty_ppv)
                binding.tvEmpty.visibility = View.VISIBLE
            } else {
                loadContent(ContentType.LIVE, ppvCategories.first().categoryId)
            }
            return
        }

        val idsPpv = ppvCategories.mapNotNull { it.categoryId }.toSet()
        val nombresPorId = ppvCategories.associate { it.categoryId to it.categoryName.orEmpty() }

        // Se busca sobre el catálogo ya cargado en memoria: es instantáneo
        val resultados = Catalog.live
            .filter { it.categoryId in idsPpv }
            .filter { canal ->
                canal.name.contains(q, ignoreCase = true) ||
                    nombresPorId[canal.categoryId].orEmpty().contains(q, ignoreCase = true)
            }
            .take(300)

        currentCategoryId = null
        refreshCategorySelection()
        adapter.submitList(resultados)
        currentItems = resultados

        binding.tvEmpty.setText(
            when {
                ppvCategories.isEmpty() -> R.string.empty_ppv
                Catalog.live.isEmpty() -> R.string.search_loading_catalog
                else -> R.string.ppv_no_match
            }
        )
        binding.tvEmpty.visibility = if (resultados.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------------- Radios del mundo ----------------

    private fun showRadio() {
        binding.tvSectionTitle.setText(R.string.section_radio)
        binding.tvSectionTitle.visibility = View.VISIBLE
        renderRadioChips()
        loadRadio(RadioCatalog.countries.first())
    }

    private fun renderRadioChips() {
        binding.categoryContainer.removeAllViews()
        RadioCatalog.countries.forEach { country ->
            val chip: TextView =
                ItemCategoryBinding.inflate(layoutInflater, binding.categoryContainer, false).root
            chip.text = "${country.flag}  ${country.name}"
            chip.tag = country.code
            Appearance.applyChipState(chip, country.code == currentRadioCode)
            chip.setOnClickListener { loadRadio(country) }
            binding.categoryContainer.addView(chip)
        }
    }

    private fun loadRadio(country: RadioCatalog.Country) {
        currentRadioCode = country.code
        // Repinta los chips para que se vea qué país está abierto
        for (i in 0 until binding.categoryContainer.childCount) {
            val chip = binding.categoryContainer.getChildAt(i) as? TextView ?: continue
            Appearance.applyChipState(chip, chip.tag == country.code)
        }
        setLoading(true)
        binding.tvEmpty.visibility = View.GONE
        binding.tvSectionTitle.text = "${country.flag}  ${country.name} · ${country.genres}"

        RadioCatalog.load(this, country) { items, error ->
            if (isFinishing || isDestroyed) return@load
            setLoading(false)
            adapter.submitList(items)
            binding.tvEmpty.text = error ?: getString(R.string.empty_radio)
            binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    // ---------------- Historial y Favoritos ----------------

    private fun showHistory() {
        setLoading(false)
        binding.categoryContainer.removeAllViews()
        val items = History.getAll(this)
        binding.tvSectionTitle.setText(R.string.section_history)
        binding.tvSectionTitle.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitList(items)
        binding.tvEmpty.setText(R.string.empty_history)
        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showFavorites() {
        setLoading(false)
        binding.categoryContainer.removeAllViews()
        renderFavoriteFilters()
        applyFavoriteFilter()
    }

    /** Chips de tipo dentro de Favoritos: Todos / Canales / Radios / Películas / Series. */
    private fun renderFavoriteFilters() {
        binding.favFilterContainer.removeAllViews()

        data class Filtro(val etiqueta: String, val tipo: ContentType?, val radio: Boolean)
        val filtros = listOf(
            Filtro(getString(R.string.fav_all), null, false),
            Filtro(getString(R.string.tab_live), ContentType.LIVE, false),
            Filtro(getString(R.string.fav_radios), ContentType.LIVE, true),
            Filtro(getString(R.string.tab_movies), ContentType.MOVIE, false),
            Filtro(getString(R.string.tab_series), ContentType.SERIES, false)
        )

        filtros.forEach { f ->
            val chip: TextView =
                ItemCategoryBinding.inflate(layoutInflater, binding.favFilterContainer, false).root
            chip.text = f.etiqueta
            val activo = f.tipo == favFilter && f.radio == favIsRadio
            Appearance.applyChipState(chip, activo)
            chip.setOnClickListener {
                favFilter = f.tipo
                favIsRadio = f.radio
                renderFavoriteFilters()
                applyFavoriteFilter()
            }
            binding.favFilterContainer.addView(chip)
        }
    }

    private fun applyFavoriteFilter() {
        val todos = Favorites.getAll(this)
        // Las radios son LIVE pero traen su propia URL: así se separan de los canales
        val favs = when {
            favFilter == null -> todos
            favIsRadio -> todos.filter { it.type == ContentType.LIVE && it.streamUrl != null }
            favFilter == ContentType.LIVE -> todos.filter { it.type == ContentType.LIVE && it.streamUrl == null }
            else -> todos.filter { it.type == favFilter }
        }
        adapter.submitList(favs)
        binding.tvEmpty.setText(R.string.empty_favorites)
        binding.tvEmpty.visibility = if (favs.isEmpty()) View.VISIBLE else View.GONE
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
        if (item.type != ContentType.MOVIE || Appearance.getMovieClick(this) == Appearance.CLICK_PLAY) {
            History.add(this, item)
        }
        // Las radios ya traen su URL; el resto se arma con los datos de la sesión
        item.streamUrl?.let { url ->
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_URL, url)
                    .putExtra(PlayerActivity.EXTRA_TITLE, item.name)
                    .putExtra(PlayerActivity.EXTRA_ITEM_ID, item.id)
                    .putExtra(PlayerActivity.EXTRA_ITEM_ICON, item.icon)
                    .putExtra(PlayerActivity.EXTRA_ITEM_CATEGORY, item.categoryId)
                    .putExtra(PlayerActivity.EXTRA_ITEM_TYPE, item.type.name)
                    .putExtra(PlayerActivity.EXTRA_ITEM_EXT, item.containerExtension)
            )
            return
        }

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
            ContentType.MOVIE -> {
                if (Appearance.getMovieClick(this) == Appearance.CLICK_DETAILS) {
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
            }
            ContentType.SERIES -> startActivity(
                Intent(this, SeriesDetailActivity::class.java)
                    .putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.id)
                    .putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.name)
            )
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    // ---------------- Ciclo de vida ----------------

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) {
            applyHomeOrientation()
            binding.tvToolbarTitle.applyBrandGradient()
            highlightNav()
            applyLayoutMode(section)
            if (section in listOf(Section.HISTORY, Section.FAVORITES)) {
                selectSection(section)   // refresca al volver del reproductor
            }
            if (section == Section.HOME) showHome()   // re-dibuja con la paleta vigente
        }
    }

    override fun onPause() {
        super.onPause()
        stopCarousel()
    }

    override fun onDestroy() {
        RadioCatalog.cancel()
        stopCarousel()
        Catalog.removeListener(catalogListener)
        super.onDestroy()
    }
}
