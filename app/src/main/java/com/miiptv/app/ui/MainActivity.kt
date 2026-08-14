package com.miiptv.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.miiptv.app.R
import com.miiptv.app.api.*
import com.miiptv.app.databinding.ActivityMainBinding
import com.miiptv.app.databinding.ItemCategoryBinding
import com.miiptv.app.util.Favorites
import com.miiptv.app.util.Parental
import com.miiptv.app.util.PinDialog
import com.miiptv.app.util.applyBrandGradient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ContentAdapter

    private var currentTab: ContentType = ContentType.LIVE
    private var currentCategoryId: String? = null
    private var categories: List<Category> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (!Session.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        adapter = ContentAdapter(onClick = { item -> openItem(item) })
        binding.recyclerChannels.layoutManager = GridLayoutManager(this, 1)
        binding.recyclerChannels.adapter = adapter

        binding.tvToolbarTitle.applyBrandGradient()

        binding.tabLive.setOnClickListener { selectTab(ContentType.LIVE) }
        binding.tabMovies.setOnClickListener { selectTab(ContentType.MOVIE) }
        binding.tabSeries.setOnClickListener { selectTab(ContentType.SERIES) }
        binding.tabFavorites.setOnClickListener { showFavorites() }

        selectTab(ContentType.LIVE)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_search -> startActivity(Intent(this, SearchActivity::class.java))
            R.id.action_multiscreen -> startActivity(Intent(this, MultiScreenActivity::class.java))
            R.id.action_parental -> openParentalSettings()
        }
        return true
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

    // ---------------- Pestañas ----------------

    private fun selectTab(type: ContentType) {
        currentTab = type
        highlightTab(type)
        binding.tvEmpty.visibility = View.GONE
        loadCategories(type)
    }

    private fun highlightTab(type: ContentType) {
        val selectedColor = resources.getColor(com.miiptv.app.R.color.brand_accent, theme)
        val normalColor = resources.getColor(com.miiptv.app.R.color.text_muted, theme)
        binding.tabLive.setTextColor(if (type == ContentType.LIVE) selectedColor else normalColor)
        binding.tabMovies.setTextColor(if (type == ContentType.MOVIE) selectedColor else normalColor)
        binding.tabSeries.setTextColor(if (type == ContentType.SERIES) selectedColor else normalColor)
        binding.tabFavorites.setTextColor(normalColor)
    }

    private fun loadCategories(type: ContentType) {
        setLoading(true)
        val call = when (type) {
            ContentType.LIVE -> Session.api(this).getLiveCategories(Session.username(this), Session.password(this))
            ContentType.MOVIE -> Session.api(this).getVodCategories(Session.username(this), Session.password(this))
            ContentType.SERIES -> Session.api(this).getSeriesCategories(Session.username(this), Session.password(this))
        }
        call.enqueue(object : Callback<List<Category>> {
            override fun onResponse(call: Call<List<Category>>, response: Response<List<Category>>) {
                categories = response.body().orEmpty()
                renderCategoryChips(categories)
                if (categories.isNotEmpty()) {
                    loadContent(type, categories.first().categoryId)
                } else {
                    loadContent(type, null)
                }
            }

            override fun onFailure(call: Call<List<Category>>, t: Throwable) {
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
            chip.setOnClickListener {
                if (Parental.isCategoryLocked(this, cat.categoryId)) {
                    PinDialog.ask(this) { loadContent(currentTab, cat.categoryId) }
                } else {
                    loadContent(currentTab, cat.categoryId)
                }
            }
            binding.categoryContainer.addView(chip)
        }
    }

    private fun loadContent(type: ContentType, categoryId: String?) {
        currentCategoryId = categoryId
        setLoading(true)
        when (type) {
            ContentType.LIVE -> Session.api(this).getLiveStreams(Session.username(this), Session.password(this), categoryId = categoryId)
                .enqueue(simpleCallback { it.map { s -> s.toContentItem() } })
            ContentType.MOVIE -> Session.api(this).getVodStreams(Session.username(this), Session.password(this), categoryId = categoryId)
                .enqueue(simpleCallback { it.map { s -> s.toContentItem() } })
            ContentType.SERIES -> Session.api(this).getSeries(Session.username(this), Session.password(this), categoryId = categoryId)
                .enqueue(simpleCallback { it.map { s -> s.toContentItem() } })
        }
    }

    private fun <T> simpleCallback(map: (List<T>) -> List<ContentItem>) = object : Callback<List<T>> {
        override fun onResponse(call: Call<List<T>>, response: Response<List<T>>) {
            setLoading(false)
            val items = map(response.body().orEmpty())
            adapter.submitList(items)
            binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }

        override fun onFailure(call: Call<List<T>>, t: Throwable) {
            setLoading(false)
            Toast.makeText(this@MainActivity, "Error cargando contenido: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showFavorites() {
        highlightTab(ContentType.LIVE) // limpia resaltado de las otras
        binding.tabFavorites.setTextColor(resources.getColor(com.miiptv.app.R.color.brand_accent, theme))
        binding.categoryContainer.removeAllViews()
        val favs = Favorites.getAll(this)
        adapter.submitList(favs)
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
        when (item.type) {
            ContentType.LIVE -> {
                val url = Session.liveStreamUrl(this, item.id)
                startActivity(Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_URL, url)
                    .putExtra(PlayerActivity.EXTRA_TITLE, item.name))
            }
            ContentType.MOVIE -> {
                val url = Session.vodStreamUrl(this, item.id, item.containerExtension ?: "mp4")
                startActivity(Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_URL, url)
                    .putExtra(PlayerActivity.EXTRA_TITLE, item.name))
            }
            ContentType.SERIES -> {
                startActivity(Intent(this, SeriesDetailActivity::class.java)
                    .putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.id)
                    .putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.name))
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
