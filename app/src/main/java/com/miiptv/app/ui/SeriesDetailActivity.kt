package com.miiptv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.miiptv.app.R
import com.miiptv.app.api.ContentType
import com.miiptv.app.api.Episode
import com.miiptv.app.api.Session
import com.miiptv.app.api.SeriesInfoResponse
import com.miiptv.app.databinding.ActivitySeriesDetailBinding
import com.miiptv.app.databinding.ItemCategoryBinding
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.util.EpisodeProgress
import com.miiptv.app.util.ErrorDiagnosis
import com.miiptv.app.util.RemoteControl
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SeriesDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERIES_ID = "extra_series_id"
        const val EXTRA_SERIES_NAME = "extra_series_name"
        const val EXTRA_SERIES_ICON = "extra_series_icon"
        const val EXTRA_SERIES_CATEGORY = "extra_series_category"
        /**
         * Desde "Continuar viendo": al cargar la serie se abre directo este
         * episodio (en el minuto donde quedó), en vez de solo mostrar la lista.
         */
        const val EXTRA_RESUME_EPISODE_ID = "extra_resume_episode_id"
    }

    private lateinit var binding: ActivitySeriesDetailBinding
    private var episodesBySeason: Map<String, List<Episode>> = emptyMap()
    private var currentSeason: List<Episode> = emptyList()
    private var currentSeasonKey: String? = null
    private lateinit var adapter: EpisodeAdapter

    private var seriesId: Int = -1
    /** Episodio a abrir solo apenas carga la serie (ver EXTRA_RESUME_EPISODE_ID); se usa una vez. */
    private var episodioAReanudar: String? = null

    /** Id del episodio elegido justo antes de abrir el reproductor (ver onResume). */
    private var idAlAbrirReproductor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
        binding = ActivitySeriesDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        seriesId = intent.getIntExtra(EXTRA_SERIES_ID, -1)
        binding.toolbar.title = intent.getStringExtra(EXTRA_SERIES_NAME)
        // Solo la primera vez: si la pantalla se recrea (rotación), no se
        // vuelve a abrir el reproductor por su cuenta.
        if (savedInstanceState == null) {
            episodioAReanudar = intent.getStringExtra(EXTRA_RESUME_EPISODE_ID)
        }

        adapter = EpisodeAdapter { episode -> playEpisode(episode) }
        binding.recyclerEpisodes.layoutManager = LinearLayoutManager(this)
        binding.recyclerEpisodes.adapter = adapter

        if (seriesId == -1) { finish(); return }
        loadSeriesInfo(seriesId)
    }

    private fun loadSeriesInfo(seriesId: Int) {
        binding.progressBar.visibility = View.VISIBLE
        Session.api(this).getSeriesInfo(Session.username(this), Session.password(this), seriesId = seriesId)
            .enqueue(object : Callback<SeriesInfoResponse> {
                override fun onResponse(call: Call<SeriesInfoResponse>, response: Response<SeriesInfoResponse>) {
                    // Si el usuario ya salió, no se debe abrir el reproductor "solo".
                    if (isFinishing || isDestroyed) return
                    binding.progressBar.visibility = View.GONE
                    if (!response.isSuccessful) {
                        val causa = ErrorDiagnosis.causaDeHttp(response.code())
                        Toast.makeText(this@SeriesDetailActivity, ErrorDiagnosis.mensaje(this@SeriesDetailActivity, causa), Toast.LENGTH_LONG).show()
                        return
                    }
                    episodesBySeason = response.body()?.episodes ?: emptyMap()
                    renderSeasonChips()
                    // Desde "Continuar viendo": se abre la temporada del
                    // episodio pendiente y se lo reproduce directo.
                    val pendiente = episodioAReanudar
                    episodioAReanudar = null
                    val temporada = pendiente?.let { id ->
                        episodesBySeason.entries.firstOrNull { (_, eps) -> eps.any { it.id == id } }?.key
                    }
                    if (temporada != null) {
                        showSeason(temporada)
                        currentSeason.firstOrNull { it.id == pendiente }?.let { playEpisode(it) }
                    } else {
                        episodesBySeason.keys.firstOrNull()?.let { showSeason(it) }
                    }
                }

                override fun onFailure(call: Call<SeriesInfoResponse>, t: Throwable) {
                    if (isFinishing || isDestroyed) return
                    binding.progressBar.visibility = View.GONE
                    val causa = ErrorDiagnosis.causaDeFallo(this@SeriesDetailActivity, t)
                    Toast.makeText(
                        this@SeriesDetailActivity,
                        ErrorDiagnosis.mensaje(this@SeriesDetailActivity, causa),
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    private fun renderSeasonChips() {
        binding.seasonContainer.removeAllViews()
        episodesBySeason.keys.forEach { season ->
            val chip: TextView = ItemCategoryBinding.inflate(layoutInflater, binding.seasonContainer, false).root
            chip.text = "Temporada $season"
            chip.tag = season
            chip.setOnClickListener { showSeason(season) }
            binding.seasonContainer.addView(chip)
        }
        repaintSeasonChips()
    }

    /** Igual que refreshCategorySelection() en la pantalla principal: marca cuál está abierta. */
    private fun repaintSeasonChips() {
        for (i in 0 until binding.seasonContainer.childCount) {
            val chip = binding.seasonContainer.getChildAt(i) as TextView
            Appearance.applyChipState(chip, chip.tag == currentSeasonKey)
        }
    }

    private fun showSeason(season: String) {
        currentSeasonKey = season
        currentSeason = episodesBySeason[season].orEmpty()
        adapter.submitList(currentSeason)
        repaintSeasonChips()
    }

    override fun onResume() {
        super.onResume()
        // Al volver de ver un episodio (PlayerActivity), sin esto ninguna
        // fila quedaba visualmente marcada: la lista se ve exactamente igual
        // recién abierta que después de haber elegido algo, y con el control
        // remoto no hay forma de saber dónde se estaba parado.
        val id = idAlAbrirReproductor
        idAlAbrirReproductor = null
        if (id == null || !RemoteControl.isEnabled(this)) return
        val posicion = currentSeason.indexOfFirst { it.id == id }
        if (posicion < 0) return
        binding.recyclerEpisodes.post {
            val vista = binding.recyclerEpisodes.layoutManager?.findViewByPosition(posicion)
            RemoteControl.focusWhenReady(vista ?: binding.recyclerEpisodes)
        }
    }

    /**
     * Además del episodio elegido, se manda la temporada completa para que el
     * reproductor pueda encadenar automáticamente con el siguiente capítulo.
     */
    private fun playEpisode(episode: Episode) {
        idAlAbrirReproductor = episode.id
        val urls = ArrayList(currentSeason.map {
            Session.seriesEpisodeUrl(this, it.id, it.containerExtension ?: "mp4")
        })
        val titles = ArrayList(currentSeason.mapIndexed { i, ep ->
            "E${ep.episodeNum ?: (i + 1)} — ${ep.title ?: "Episodio"}"
        })
        val index = currentSeason.indexOfFirst { it.id == episode.id }.coerceAtLeast(0)
        val episodeUrl = urls.getOrElse(index) {
            Session.seriesEpisodeUrl(this, episode.id, episode.containerExtension ?: "mp4")
        }

        // Recuperar la posición donde se dejó este episodio (si existe)
        val resumeMs = EpisodeProgress.get(this, episodeUrl)

        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, episodeUrl)
                .putExtra(PlayerActivity.EXTRA_TITLE, titles.getOrElse(index) { episode.title ?: "Episodio" })
                .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_URLS, urls)
                .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_TITLES, titles)
                .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, index)
                .putExtra(PlayerActivity.EXTRA_RESUME_MS, resumeMs)
                // Sin el tipo, el reproductor trataba el episodio como un canal
                // en vivo: no guardaba ni retomaba la posición (ese código
                // existía pero nunca se ejecutaba) y no aplicaba el idioma
                // preferido de audio y subtítulos.
                .putExtra(PlayerActivity.EXTRA_ITEM_TYPE, ContentType.SERIES.name)
                .putExtra(PlayerActivity.EXTRA_ITEM_ID, seriesId)
                .putExtra(PlayerActivity.EXTRA_ITEM_ICON, intent.getStringExtra(EXTRA_SERIES_ICON))
                .putExtra(PlayerActivity.EXTRA_ITEM_CATEGORY, intent.getStringExtra(EXTRA_SERIES_CATEGORY))
                .putExtra(PlayerActivity.EXTRA_SERIES_NAME, intent.getStringExtra(EXTRA_SERIES_NAME))
                .putStringArrayListExtra(PlayerActivity.EXTRA_EPISODE_IDS, ArrayList(currentSeason.map { it.id }))
        )
    }
}

class EpisodeAdapter(private val onClick: (Episode) -> Unit) : RecyclerView.Adapter<EpisodeAdapter.VH>() {
    private val items = mutableListOf<Episode>()

    fun submitList(newItems: List<Episode>) {
        val oldSize = items.size
        items.clear()
        items.addAll(newItems)
        // DiffUtil básico: si la temporada cambió entera es más rápido
        // hacer notifyDataSetChanged, pero con el mando eso hace que el foco
        // caiga en ningún lado. Con notifyItemRangeChanged el RecyclerView
        // reutiliza las vistas existentes y el foco sobrevive.
        if (oldSize == 0 || oldSize != newItems.size) {
            notifyDataSetChanged()
        } else {
            notifyItemRangeChanged(0, items.size)
        }
    }

    inner class VH(val view: TextView) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false) as TextView
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ep = items[position]
        holder.view.text = "E${ep.episodeNum ?: (position + 1)} — ${ep.title ?: "Episodio"}"
        holder.view.setOnClickListener { onClick(ep) }
        // Antes usaba el fondo genérico de Android (selectableItemBackground),
        // casi invisible sobre el tema oscuro de la app: con el control
        // remoto no se notaba qué fila estaba enfocada. Este es el mismo
        // anillo de foco que ya usan las tarjetas de Canales/Películas.
        RemoteControl.applyItemFocus(holder.view, RemoteControl.isEnabled(holder.view.context))
    }

    override fun getItemCount() = items.size
}
