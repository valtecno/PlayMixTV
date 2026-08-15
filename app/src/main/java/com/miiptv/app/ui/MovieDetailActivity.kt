package com.miiptv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.miiptv.app.R
import com.miiptv.app.api.*
import com.miiptv.app.databinding.ActivityMovieDetailBinding
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.Favorites
import com.miiptv.app.util.History
import com.squareup.picasso.Picasso
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Ficha de una película: carátula, sinopsis, reparto, director y datos técnicos.
 * Se abre cuando en "Personalizar" está elegido "Ver detalles primero".
 */
class MovieDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "extra_movie_id"
        const val EXTRA_NAME = "extra_movie_name"
        const val EXTRA_ICON = "extra_movie_icon"
        const val EXTRA_EXT = "extra_movie_ext"
        const val EXTRA_CATEGORY = "extra_movie_category"
    }

    private lateinit var binding: ActivityMovieDetailBinding
    private lateinit var item: ContentItem

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMovieDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id < 0) return finish()

        item = ContentItem(
            id = id,
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            icon = intent.getStringExtra(EXTRA_ICON),
            categoryId = intent.getStringExtra(EXTRA_CATEGORY),
            type = ContentType.MOVIE,
            containerExtension = intent.getStringExtra(EXTRA_EXT)
        )

        binding.toolbar.title = item.name
        binding.tvTitle.text = item.name
        if (!item.icon.isNullOrBlank()) Picasso.get().load(item.icon).into(binding.ivPoster)

        binding.btnPlay.background = Appearance.gradient(this, 10f)
        binding.btnPlay.setOnClickListener { play() }
        binding.btnFavorite.setOnClickListener {
            Favorites.toggle(this, item)
            refreshFavoriteLabel()
        }
        refreshFavoriteLabel()

        loadInfo(id)
    }

    private fun refreshFavoriteLabel() {
        val fav = Favorites.isFavorite(this, item)
        binding.btnFavorite.text = if (fav) "★  ${getString(R.string.tab_favorites)}" else "☆  ${getString(R.string.tab_favorites)}"
    }

    private fun loadInfo(id: Int) {
        binding.progressBar.visibility = View.VISIBLE
        Session.api(this).getVodInfo(Session.username(this), Session.password(this), vodId = id)
            .enqueue(object : Callback<VodInfoResponse> {
                override fun onResponse(call: Call<VodInfoResponse>, response: Response<VodInfoResponse>) {
                    if (isFinishing || isDestroyed) return
                    binding.progressBar.visibility = View.GONE
                    render(response.body()?.info, response.body()?.movieData)
                }

                override fun onFailure(call: Call<VodInfoResponse>, t: Throwable) {
                    if (isFinishing) return
                    binding.progressBar.visibility = View.GONE
                    binding.tvPlot.text = getString(R.string.movie_no_info)
                }
            })
    }

    private fun render(info: VodInfo?, data: VodMovieData?) {
        // Si el servidor manda una carátula mejor que la de la lista, la usamos
        if (!info?.image.isNullOrBlank()) Picasso.get().load(info?.image).into(binding.ivPoster)

        // Extensión real del archivo, para que la URL de reproducción sea correcta
        if (!data?.containerExtension.isNullOrBlank()) {
            item = item.copy(containerExtension = data?.containerExtension)
        }

        val meta = listOfNotNull(
            info?.releaseDate?.takeIf { it.isNotBlank() }?.take(4),
            info?.duration?.takeIf { it.isNotBlank() },
            info?.rating?.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" }
        )
        binding.tvMeta.text = meta.joinToString("  ·  ")
        binding.tvMeta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE

        val genre = info?.genre?.takeIf { it.isNotBlank() }
        binding.tvGenre.text = genre.orEmpty()
        binding.tvGenre.visibility = if (genre == null) View.GONE else View.VISIBLE

        setSection(binding.tvPlotLabel, binding.tvPlot, info?.plot)
        setSection(binding.tvCastLabel, binding.tvCast, info?.cast)
        setSection(binding.tvDirectorLabel, binding.tvDirector, info?.director)

        val nothing = listOf(info?.plot, info?.cast, info?.director).all { it.isNullOrBlank() }
        if (nothing) {
            binding.tvPlot.text = getString(R.string.movie_no_info)
            binding.tvPlot.visibility = View.VISIBLE
        }
    }

    private fun setSection(label: View, body: android.widget.TextView, value: String?) {
        val has = !value.isNullOrBlank()
        label.visibility = if (has) View.VISIBLE else View.GONE
        body.visibility = if (has) View.VISIBLE else View.GONE
        body.text = value.orEmpty()
    }

    private fun play() {
        History.add(this, item)
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
