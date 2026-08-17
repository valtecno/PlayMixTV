package com.miiptv.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
import com.miiptv.app.util.RemoteControl
import com.miiptv.app.util.PlayerFactory
import com.miiptv.app.util.PpvFilter
import com.miiptv.app.util.RadioCatalog
import com.miiptv.app.util.Servers
import com.miiptv.app.util.PinDialog
import com.miiptv.app.util.UpdateDialog
import com.miiptv.app.util.applyBrandGradient
import com.squareup.picasso.Picasso
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
    /** Chip de radios abierto ahora mismo (país o marca). */
    private var currentRadioSource: RadioCatalog.Source? = null
    /**
     * Emisoras que se están mostrando. Se le pasan enteras al reproductor para
     * poder saltar de una a otra sin volver a esta pantalla.
     */
    private var radioPlaylist: List<ContentItem> = emptyList()
    /** Última lista cargada, sin filtrar: base del buscador del perfil de niños. */
    private var currentItems: List<ContentItem> = emptyList()
    private var categories: List<Category> = emptyList()
    /** Ítem que se está mostrando ahora en el panel de previsualización de Canales (TV). */
    private var previewItem: ContentItem? = null

    /**
     * Reproductor de la previsualización. Vive con la pantalla (onStart/onStop)
     * y NUNCA suena a la vez que el reproductor a pantalla completa: al abrir
     * PlayerActivity esta activity pasa a onStop y acá se libera. Importa
     * porque las cuentas Xtream limitan las conexiones simultáneas, y dejar la
     * previsualización viva haría que el canal en pantalla completa fallara.
     */
    private var previewPlayer: ExoPlayer? = null
    private var previewMuted = false

    /**
     * Al recorrer la lista con el control remoto se pasa por muchos canales en
     * un segundo. Sin esta espera se abriría una conexión por cada uno.
     */
    private val previewDelay = Handler(Looper.getMainLooper())
    private var pendingPreview: Runnable? = null

    /** Perfil de niños: filtra a solo contenido infantil y oculta las secciones no aptas. */
    private var kidsMode: Boolean = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
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

        adapter = ContentAdapter(onClick = { item -> handleItemClick(item) })
        // Con el remoto la tarjeta enfocada se pinta y se agranda un poco; con
        // el dedo, todo queda como siempre.
        adapter.remoteMode = RemoteControl.isEnabled(this)
        binding.recyclerChannels.layoutManager = GridLayoutManager(this, 1)
        binding.recyclerChannels.adapter = adapter

        binding.btnPreviewPlay.setOnClickListener { previewItem?.let { openItem(it) } }
        binding.previewPlayRow.setOnClickListener { previewItem?.let { openItem(it) } }
        binding.previewThumbFrame.setOnClickListener { previewItem?.let { openItem(it) } }
        binding.btnPreviewMute.setOnClickListener { togglePreviewMute() }

        binding.tvToolbarTitle.applyBrandGradient()

        kidsMode = KidsMode.isActive(this)

        setupNav()
        setupPpvSearch()
        setupKidsSearch()
        setupCarousel()
        applyKidsVisibility()

        selectSection(if (kidsMode) Section.LIVE else Section.HOME)

        /*
         * Foco inicial en el menú superior.
         *
         * Sin esto, al abrir la app con un control remoto no hay nada enfocado:
         * la primera flecha que se pulsa se "pierde" (Android la usa para elegir
         * un primer destino) y parece que el remoto no responde. Dejando el
         * menú enfocado de entrada, la app arranca mostrando dónde está parado.
         */
        if (RemoteControl.isEnabled(this)) {
            RemoteControl.focusWhenReady(
                if (kidsMode) binding.navLive else binding.navHome
            )
        }
        // Si la app abre directo en el perfil de niños (venía activo de antes),
        // hay que avisar la regla igual que cuando se activa con el botón.
        if (kidsMode) {
            Toast.makeText(this, R.string.kids_mode_on, Toast.LENGTH_LONG).show()
        }
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

        binding.homeArea.visibility = if (isHome) View.VISIBLE else View.GONE
        // Ocultar el cuerpo entero, no solo la lista de adentro: los dos son
        // "0dp + weight 1" en el LinearLayout raíz, así que si bodyContainer
        // seguía visible se quedaba con la mitad del alto y el Inicio solo
        // llegaba hasta la mitad de la pantalla.
        binding.bodyContainer.visibility = if (isHome) View.GONE else View.VISIBLE
        binding.recyclerChannels.visibility = if (isHome) View.GONE else View.VISIBLE
        binding.categoryScroll.visibility = if (isBrowse) View.VISIBLE else View.GONE
        binding.favFilterScroll.visibility =
            if (newSection == Section.FAVORITES) View.VISIBLE else View.GONE
        if (newSection != Section.PPV) binding.etPpvSearch.visibility = View.GONE
        binding.etKidsSearch.visibility =
            if (kidsMode && newSection in listOf(Section.LIVE, Section.MOVIES, Section.SERIES))
                View.VISIBLE else View.GONE

        applyLayoutMode(newSection)
        updatePreviewVisibility(newSection)
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

    /**
     * Panel de previsualización: foto grande + botón "Reproducir" al lado de la
     * lista, en vez de abrir el reproductor de una. Solo tiene sentido en TV
     * (control remoto, se navega la lista con el foco) y solo en Canales; en
     * móvil se sigue abriendo directo al tocar, como siempre.
     */
    private fun showPreviewFor(s: Section): Boolean = s == Section.LIVE || s == Section.PPV

    private fun updatePreviewVisibility(newSection: Section) {
        val conPreview = showPreviewFor(newSection)
        binding.previewPanel.visibility = if (conPreview) View.VISIBLE else View.GONE
        if (!conPreview) {
            previewItem = null
            stopPreview()
        }
        // Siempre se recalcula, con y sin previsualización. Antes la rama "sin
        // previsualización" solo devolvía el contenedor a horizontal y dejaba
        // listColumn con los parámetros del modo apilado (height=0). En un
        // LinearLayout horizontal el peso reparte solo a lo ancho, así que ese
        // height=0 se quedaba en cero de verdad: la columna colapsaba y en móvil
        // desaparecían Películas, Series y Radios (y sus chips de categoría).
        applyPreviewLayout(conPreview)
    }

    /**
     * Coloca la lista y el panel de previsualización.
     *
     * Solo hay un caso apilado: móvil CON previsualización, donde el video va
     * arriba en 16:9 y la lista debajo. En todos los demás (TV, o cualquier
     * sección sin previsualización) el cuerpo va en horizontal y la lista ocupa
     * el alto completo.
     */
    private fun applyPreviewLayout(conPreview: Boolean) {
        val apilado = conPreview && DeviceMode.isMobile(this)

        binding.bodyContainer.orientation =
            if (apilado) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        val lista = binding.listColumn.layoutParams as LinearLayout.LayoutParams
        val panel = binding.previewPanel.layoutParams as LinearLayout.LayoutParams
        val marco = binding.previewThumbFrame.layoutParams as LinearLayout.LayoutParams

        if (apilado) {
            lista.width = LinearLayout.LayoutParams.MATCH_PARENT
            lista.height = 0
            lista.weight = 1f

            panel.width = LinearLayout.LayoutParams.MATCH_PARENT
            panel.height = LinearLayout.LayoutParams.WRAP_CONTENT
            panel.weight = 0f

            // Marco de video en 16:9, sin pasarse de un tercio de la pantalla
            val metrics = resources.displayMetrics
            marco.width = LinearLayout.LayoutParams.MATCH_PARENT
            marco.height = (metrics.widthPixels * 9 / 16)
                .coerceAtMost((metrics.heightPixels * 0.34f).toInt())
            marco.weight = 0f
        } else {
            lista.width = 0
            lista.height = LinearLayout.LayoutParams.MATCH_PARENT
            lista.weight = 1f

            panel.width = 0
            panel.height = LinearLayout.LayoutParams.MATCH_PARENT
            panel.weight = 1.4f

            marco.width = LinearLayout.LayoutParams.MATCH_PARENT
            marco.height = 0
            marco.weight = 1f
        }

        binding.listColumn.layoutParams = lista
        binding.previewPanel.layoutParams = panel
        binding.previewThumbFrame.layoutParams = marco
    }

    private fun handleItemClick(item: ContentItem) {
        if (!showPreviewFor(section)) {
            openItem(item)
            return
        }
        // Segundo toque sobre el canal que ya se está previsualizando: pantalla completa.
        if (previewItem?.id == item.id && previewPlayer?.isPlaying == true) {
            openItem(item)
        } else {
            showPreview(item)
        }
    }

    /** Solo la ficha: nombre, categoría y logo. No conecta nada. */
    private fun showPreviewCard(item: ContentItem) {
        previewItem = item
        stopPreview()
        binding.tvPreviewTitle.text = item.name
        binding.tvPreviewCategory.text =
            categories.firstOrNull { it.categoryId == item.categoryId }?.categoryName.orEmpty()
        if (!item.icon.isNullOrBlank()) {
            Picasso.get().load(item.icon).into(binding.ivPreviewLogo)
        } else {
            binding.ivPreviewLogo.setImageDrawable(null)
        }
    }

    /** Ficha + arranque de la previsualización en video. */
    private fun showPreview(item: ContentItem) {
        showPreviewCard(item)

        // Categoría bloqueada: se muestra la ficha pero no se reproduce nada
        // hasta que se ingrese el PIN desde el botón de pantalla completa.
        if (Parental.isCategoryLocked(this, item.categoryId)) {
            showPreviewMessage(getString(R.string.preview_locked))
            return
        }

        schedulePreview(item)
    }

    /** Espera un momento antes de conectar: evita abrir una conexión por canal recorrido. */
    private fun schedulePreview(item: ContentItem) {
        pendingPreview?.let { previewDelay.removeCallbacks(it) }
        showPreviewIdle(loading = true)

        val tarea = Runnable {
            if (isFinishing || isDestroyed) return@Runnable
            if (previewItem?.id != item.id) return@Runnable
            playPreview(item)
        }
        pendingPreview = tarea
        previewDelay.postDelayed(tarea, PREVIEW_DELAY_MS)
    }

    private fun playPreview(item: ContentItem) {
        val exo = previewPlayer ?: buildPreviewPlayer().also { previewPlayer = it }
        binding.tvPreviewError.visibility = View.GONE
        exo.setMediaItem(MediaItem.fromUri(Session.liveStreamUrl(this, item.id)))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun buildPreviewPlayer(): ExoPlayer {
        val exo = PlayerFactory.build(this)
        exo.volume = if (previewMuted) 0f else 1f
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (isFinishing || isDestroyed) return
                binding.previewProgress.visibility =
                    if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) {
                    // Recién con imagen se tapa el logo
                    binding.previewPlayer.visibility = View.VISIBLE
                    binding.ivPreviewLogo.visibility = View.GONE
                    binding.tvPreviewError.visibility = View.GONE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isFinishing || isDestroyed) return
                showPreviewMessage(getString(R.string.preview_error))
            }
        })
        binding.previewPlayer.player = exo
        return exo
    }

    /** Vuelve al estado "solo logo", opcionalmente con el indicador de carga. */
    private fun showPreviewIdle(loading: Boolean) {
        binding.previewPlayer.visibility = View.GONE
        binding.ivPreviewLogo.visibility = View.VISIBLE
        binding.tvPreviewError.visibility = View.GONE
        binding.previewProgress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showPreviewMessage(texto: String) {
        binding.previewProgress.visibility = View.GONE
        binding.previewPlayer.visibility = View.GONE
        binding.ivPreviewLogo.visibility = View.VISIBLE
        binding.tvPreviewError.text = texto
        binding.tvPreviewError.visibility = View.VISIBLE
    }

    private fun togglePreviewMute() {
        previewMuted = !previewMuted
        previewPlayer?.volume = if (previewMuted) 0f else 1f
        binding.btnPreviewMute.setImageResource(
            if (previewMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
    }

    /** Corta la previsualización y suelta la conexión con el panel. */
    private fun stopPreview() {
        pendingPreview?.let { previewDelay.removeCallbacks(it) }
        pendingPreview = null
        previewPlayer?.let {
            it.stop()
            it.clearMediaItems()
        }
        if (::binding.isInitialized) showPreviewIdle(loading = false)
    }

    private fun releasePreview() {
        pendingPreview?.let { previewDelay.removeCallbacks(it) }
        pendingPreview = null
        binding.previewPlayer.player = null
        previewPlayer?.release()
        previewPlayer = null
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
        // El separador solo tiene sentido si hay algo a los dos lados
        binding.homeDivider.visibility =
            if (novedades.itemCount > 0 && recientes.itemCount > 0) View.VISIBLE else View.GONE

        // Antes, si el catálogo venía vacío el cartel se ocultaba y quedaba una
        // pantalla en blanco sin explicación. Ahora se distingue entre "todavía
        // cargando", "no hay nada" y "falló, y este fue el motivo".
        val vacio = novedades.itemCount == 0 && recientes.itemCount == 0
        val motivo = Catalog.lastError
        when {
            !vacio -> binding.tvHomeEmpty.visibility = View.GONE
            // Mientras baja el catálogo se avisa, en vez de dejar el Inicio en
            // blanco sin explicación: en el Sistema XL puede tardar bastante.
            Catalog.isLoading -> {
                binding.tvHomeEmpty.setText(R.string.home_loading)
                binding.tvHomeEmpty.visibility = View.VISIBLE
            }
            motivo != null -> {
                binding.tvHomeEmpty.text = getString(R.string.catalog_error, motivo)
                binding.tvHomeEmpty.visibility = View.VISIBLE
            }
            else -> {
                binding.tvHomeEmpty.setText(R.string.empty_list)
                binding.tvHomeEmpty.visibility = View.VISIBLE
            }
        }

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

        // Separador: línea vertical entre columnas en TV, horizontal entre
        // secciones apiladas en móvil.
        val grosor = (resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val sep = binding.homeDivider.layoutParams as LinearLayout.LayoutParams
        val margen = (10 * resources.displayMetrics.density).toInt()
        if (movil) {
            sep.width = LinearLayout.LayoutParams.MATCH_PARENT
            sep.height = grosor
            sep.setMargins(margen, 0, margen, 0)
            binding.homeDivider.setBackgroundResource(R.drawable.bg_divider_horizontal)
        } else {
            sep.width = grosor
            sep.height = LinearLayout.LayoutParams.MATCH_PARENT
            sep.setMargins(0, margen, 0, margen)
            binding.homeDivider.setBackgroundResource(R.drawable.bg_divider_vertical)
        }
        binding.homeDivider.layoutParams = sep
    }

    private fun setupCarousel() {
        applyHomeOrientation()
        carouselAdapter = CarouselAdapter(onClick = { item -> openItem(item) })
        recentAdapter = CarouselAdapter(onClick = { item -> openItem(item) })

        // Una carátula por columna, cambiando de a una. El carrusel se encarga
        // de darle forma de carátula y centrarla en el espacio disponible.
        novedades = AutoCarousel(binding.recyclerCarousel, carouselAdapter, delayMs = 5000L)
        recientes = AutoCarousel(binding.recyclerRecent, recentAdapter, delayMs = 5000L)
        novedades.attach()
        recientes.attach()

        // El catálogo se carga una sola vez y lo reutiliza también el buscador
        Catalog.ensureLoaded(this, onUpdate = catalogListener)

        // Búsqueda de actualizaciones en segundo plano. Con manual = false solo
        // avisa si hay algo nuevo, y como mucho una vez cada 12 horas.
        UpdateDialog.check(this, manual = false)
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
                }.let { list -> reorderPreferredFirst(list) }
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
                    val elegida = categories.first()
                    loadContent(type, elegida.categoryId)
                }
            }

            override fun onFailure(call: Call<List<Category>>, t: Throwable) {
                if (isFinishing) return
                setLoading(false)
                Toast.makeText(this@MainActivity, "Error cargando categorías: ${t.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    /**
     * Pone primera en la lista (izquierda del todo) la carpeta/categoría que
     * corresponde según el sistema conectado ahora mismo (Sistema L / Sistema XL),
     * tanto para elegirla como contenido por defecto como para su posición visual
     * en los chips. Cuál es la carpeta preferida de cada servidor se declara en
     * gradle.properties (playmix.servers), porque cambia de panel a panel:
     * Sistema L trae "Cinema HD HQ" donde Sistema XL trae "Cinema Latino".
     * Si no se encuentra ninguna coincidencia (otro servidor, o el panel no la
     * trae esta vez), la lista queda igual que vino del servidor.
     */
    private fun reorderPreferredFirst(list: List<Category>): List<Category> {
        // Qué carpeta prefiere cada servidor ya no está escrito acá: viene de
        // gradle.properties, junto a la definición del servidor. Antes esto era
        // un when contra Servers.all[0]/[1], o sea contra la POSICIÓN en la
        // lista: agregar un tercer servidor al principio hacía que Sistema L
        // empezara a abrir las carpetas de Sistema XL, sin ningún error visible.
        val servidor = Servers.current(this)
        val candidatas: List<String> = when (section) {
            Section.LIVE -> servidor?.preferidas(Servers.Preferidas.CANALES)
            Section.PPV -> servidor?.preferidas(Servers.Preferidas.PPV)
            Section.MOVIES -> servidor?.preferidas(Servers.Preferidas.PELICULAS)
            else -> null
        }.orEmpty()
        if (candidatas.isEmpty()) return list
        // Para cada candidata, primero se busca una carpeta con el nombre EXACTO
        // (evita que "2026" agarre por error "Copa Mundial 2026" o "Nominados al
        // Oscar 2026", que también contienen "2026"). Solo si no hay nombre exacto
        // se usa una coincidencia más floja (contiene la frase), útil para casos
        // como "Chile Primera 08/15" o "PPV Futbol Sudamericano".
        var preferida: Category? = null
        for (candidata in candidatas) {
            val candidataNorm = PpvFilter.normalizeLoose(candidata)
            preferida = list.firstOrNull { cat ->
                PpvFilter.normalizeLoose(cat.categoryName.orEmpty()) == candidataNorm
            } ?: list.firstOrNull { cat ->
                PpvFilter.normalizeLoose(cat.categoryName.orEmpty()).contains(candidataNorm)
            }
            if (preferida != null) break
        }
        if (preferida == null) return list
        return listOf(preferida) + list.filter { it.categoryId != preferida.categoryId }
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
            if (showPreviewFor(section)) {
                // Solo la ficha, sin arrancar el video: entrar a una categoría no
                // debe disparar audio ni gastar una conexión del panel por su cuenta.
                if (items.isNotEmpty()) showPreviewCard(items.first()) else {
                    previewItem = null
                    stopPreview()
                }
            }
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
        loadRadio(currentRadioSource ?: RadioCatalog.sources.first())
    }

    private fun renderRadioChips() {
        binding.categoryContainer.removeAllViews()
        RadioCatalog.sources.forEach { source ->
            val chip: TextView =
                ItemCategoryBinding.inflate(layoutInflater, binding.categoryContainer, false).root
            chip.text = "${source.flag}  ${source.name}"
            chip.tag = source.id
            Appearance.applyChipState(chip, source.id == currentRadioSource?.id)
            chip.setOnClickListener { loadRadio(source) }
            binding.categoryContainer.addView(chip)
        }
    }

    private fun loadRadio(source: RadioCatalog.Source) {
        currentRadioSource = source
        // Repinta los chips para que se vea cuál está abierto
        for (i in 0 until binding.categoryContainer.childCount) {
            val chip = binding.categoryContainer.getChildAt(i) as? TextView ?: continue
            Appearance.applyChipState(chip, chip.tag == source.id)
        }
        setLoading(true)
        binding.tvEmpty.visibility = View.GONE
        binding.tvSectionTitle.text = "${source.flag}  ${source.name} · ${source.genres}"

        RadioCatalog.load(this, source) { items, error ->
            if (isFinishing || isDestroyed) return@load
            // Llegó tarde: el usuario ya tocó otro chip
            if (currentRadioSource?.id != source.id) return@load
            setLoading(false)
            radioPlaylist = items
            adapter.submitList(items)
            binding.tvEmpty.text = error
                ?: getString(R.string.empty_radio_source, source.name)
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
            startActivity(radioIntent(item, url))
            return
        }

        when (item.type) {
            ContentType.LIVE -> startActivity(liveIntent(item))
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

    /**
     * Abre una emisora en el reproductor y le lleva además la lista completa de
     * la que salió, para que los botones "anterior / siguiente" de la barra de
     * reproducción puedan saltar de emisora sin volver acá.
     *
     * La lista es la que se ve en pantalla en ese momento: el país o la marca
     * abiertos en Radios, o las radios guardadas si se entró desde Favoritos o
     * Historial.
     */
    private fun radioIntent(item: ContentItem, url: String): Intent {
        val emisoras = (if (section == Section.RADIO) radioPlaylist else adapter.currentItems)
            .filter { !it.streamUrl.isNullOrBlank() }
            .ifEmpty { listOf(item) }

        val posicion = emisoras.indexOfFirst { it.streamUrl == url }.coerceAtLeast(0)

        // Solo se etiqueta el origen cuando venimos de la sección Radios; desde
        // Favoritos o Historial la lista es mezcla y poner un país sería mentir.
        val origen = if (section == Section.RADIO) {
            currentRadioSource?.let { "${it.flag}  ${it.name}" }
        } else {
            null
        }

        return Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URL, url)
            .putExtra(PlayerActivity.EXTRA_TITLE, item.name)
            .putExtra(PlayerActivity.EXTRA_ITEM_ID, item.id)
            .putExtra(PlayerActivity.EXTRA_ITEM_ICON, item.icon)
            .putExtra(PlayerActivity.EXTRA_ITEM_CATEGORY, item.categoryId)
            .putExtra(PlayerActivity.EXTRA_ITEM_TYPE, item.type.name)
            .putExtra(PlayerActivity.EXTRA_ITEM_EXT, item.containerExtension)
            .putExtra(PlayerActivity.EXTRA_IS_RADIO, true)
            .putExtra(PlayerActivity.EXTRA_RADIO_SOURCE, origen)
            .putStringArrayListExtra(
                PlayerActivity.EXTRA_PLAYLIST_URLS,
                ArrayList(emisoras.map { it.streamUrl.orEmpty() })
            )
            .putStringArrayListExtra(
                PlayerActivity.EXTRA_PLAYLIST_TITLES,
                ArrayList(emisoras.map { it.name })
            )
            .putStringArrayListExtra(
                PlayerActivity.EXTRA_PLAYLIST_ICONS,
                ArrayList(emisoras.map { it.icon.orEmpty() })
            )
            .putIntegerArrayListExtra(
                PlayerActivity.EXTRA_PLAYLIST_IDS,
                ArrayList(emisoras.map { it.id })
            )
            .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, posicion)
    }

    /**
     * Intent de un canal en vivo, con la lista de la categoría para poder
     * zapear desde el reproductor.
     *
     * Solo viajan ids y nombres: la URL de cada canal se arma sola a partir del
     * id, así que mandarla sería duplicar datos. Y la lista se recorta a una
     * ventana alrededor del canal elegido, porque un intent con miles de
     * entradas revienta el límite de tamaño de una transacción Binder
     * (TransactionTooLargeException) y la app se cae al abrir el reproductor.
     */
    private fun liveIntent(item: ContentItem): Intent {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URL, Session.liveStreamUrl(this, item.id))
            .putExtra(PlayerActivity.EXTRA_TITLE, item.name)
            .putExtra(PlayerActivity.EXTRA_ITEM_ID, item.id)
            .putExtra(PlayerActivity.EXTRA_ITEM_ICON, item.icon)
            .putExtra(PlayerActivity.EXTRA_ITEM_CATEGORY, item.categoryId)
            .putExtra(PlayerActivity.EXTRA_ITEM_TYPE, item.type.name)
            .putExtra(PlayerActivity.EXTRA_ITEM_EXT, item.containerExtension)

        val lista = currentItems.filter { it.type == ContentType.LIVE }
        val actual = lista.indexOfFirst { it.id == item.id }
        if (actual < 0 || lista.size < 2) return intent

        val desde = (actual - ZAP_WINDOW).coerceAtLeast(0)
        val hasta = (actual + ZAP_WINDOW + 1).coerceAtMost(lista.size)
        val ventana = lista.subList(desde, hasta)

        return intent
            .putIntegerArrayListExtra(
                PlayerActivity.EXTRA_PLAYLIST_IDS, ArrayList(ventana.map { it.id })
            )
            .putStringArrayListExtra(
                PlayerActivity.EXTRA_PLAYLIST_TITLES, ArrayList(ventana.map { it.name })
            )
            .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, actual - desde)
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
            // Al volver del reproductor se rearma la previsualización del canal
            // que estaba elegido (onStop la había liberado).
            if (showPreviewFor(section)) {
                applyPreviewLayout(conPreview = true)
                previewItem?.let { showPreview(it) }
            }
            if (section == Section.HOME) showHome()   // re-dibuja con la paleta vigente
        }
    }

    override fun onStop() {
        super.onStop()
        // Se suelta la conexión al irse de la pantalla. Es imprescindible: al
        // abrir el reproductor a pantalla completa esta activity pasa por acá,
        // y si la previsualización siguiera conectada el panel podría rechazar
        // el canal por tope de conexiones simultáneas.
        releasePreview()
    }

    override fun onPause() {
        super.onPause()
        stopCarousel()
    }

    override fun onDestroy() {
        releasePreview()
        RadioCatalog.cancel()
        stopCarousel()
        Catalog.removeListener(catalogListener)
        super.onDestroy()
    }

    private companion object {
        /** Espera antes de conectar la previsualización, al recorrer la lista. */
        const val PREVIEW_DELAY_MS = 800L

        /** Canales a cada lado del elegido que viajan al reproductor para zapear. */
        const val ZAP_WINDOW = 200
    }
}
