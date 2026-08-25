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
import com.miiptv.app.api.Episode
import com.miiptv.app.api.Session
import com.miiptv.app.api.SeriesInfoResponse
import com.miiptv.app.databinding.ActivitySeriesDetailBinding
import com.miiptv.app.databinding.ItemCategoryBinding
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.util.RemoteControl
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SeriesDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERIES_ID = "extra_series_id"
        const val EXTRA_SERIES_NAME = "extra_series_name"
    }

    private lateinit var binding: ActivitySeriesDetailBinding
    private var episodesBySeason: Map<String, List<Episode>> = emptyMap()
    private var currentSeason: List<Episode> = emptyList()
    private var currentSeasonKey: String? = null
    private lateinit var adapter: EpisodeAdapter

    /** Id del episodio elegido justo antes de abrir el reproductor (ver onResume). */
    private var idAlAbrirReproductor: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
        binding = ActivitySeriesDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val seriesId = intent.getIntExtra(EXTRA_SERIES_ID, -1)
        binding.toolbar.title = intent.getStringExtra(EXTRA_SERIES_NAME)

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
                    binding.progressBar.visibility = View.GONE
                    episodesBySeason = response.body()?.episodes ?: emptyMap()
                    renderSeasonChips()
                    episodesBySeason.keys.firstOrNull()?.let { showSeason(it) }
                }

                override fun onFailure(call: Call<SeriesInfoResponse>, t: Throwable) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@SeriesDetailActivity, "Error: ${t.message}", Toast.LENGTH_LONG).show()
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

        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, urls.getOrElse(index) {
                    Session.seriesEpisodeUrl(this, episode.id, episode.containerExtension ?: "mp4")
                })
                .putExtra(PlayerActivity.EXTRA_TITLE, titles.getOrElse(index) { episode.title ?: "Episodio" })
                .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_URLS, urls)
                .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_TITLES, titles)
                .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, index)
        )
    }
}

class EpisodeAdapter(private val onClick: (Episode) -> Unit) : RecyclerView.Adapter<EpisodeAdapter.VH>() {
    private val items = mutableListOf<Episode>()

    fun submitList(newItems: List<Episode>) {
        items.clear(); items.addAll(newItems); notifyDataSetChanged()
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
