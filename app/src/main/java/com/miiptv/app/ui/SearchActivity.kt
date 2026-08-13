package com.miiptv.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.miiptv.app.api.*
import com.miiptv.app.databinding.ActivitySearchBinding
import com.miiptv.app.util.Parental
import com.miiptv.app.util.PinDialog
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: ContentAdapter
    private val allItems = mutableListOf<ContentItem>()
    private var pendingCalls = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ContentAdapter(onClick = { item -> openItem(item) })
        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = adapter

        binding.etQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadEverything()
    }

    private fun setLoading(loading: Boolean) {
        pendingCalls += if (loading) 1 else -1
        binding.progressBar.visibility = if (pendingCalls > 0) View.VISIBLE else View.GONE
    }

    private fun loadEverything() {
        setLoading(true)
        Session.api(this).getLiveStreams(Session.username(this), Session.password(this))
            .enqueue(object : Callback<List<LiveStream>> {
                override fun onResponse(call: Call<List<LiveStream>>, response: Response<List<LiveStream>>) {
                    allItems.addAll(response.body().orEmpty().map { it.toContentItem() })
                    setLoading(false)
                }
                override fun onFailure(call: Call<List<LiveStream>>, t: Throwable) { setLoading(false) }
            })

        setLoading(true)
        Session.api(this).getVodStreams(Session.username(this), Session.password(this))
            .enqueue(object : Callback<List<VodStream>> {
                override fun onResponse(call: Call<List<VodStream>>, response: Response<List<VodStream>>) {
                    allItems.addAll(response.body().orEmpty().map { it.toContentItem() })
                    setLoading(false)
                }
                override fun onFailure(call: Call<List<VodStream>>, t: Throwable) { setLoading(false) }
            })

        setLoading(true)
        Session.api(this).getSeries(Session.username(this), Session.password(this))
            .enqueue(object : Callback<List<SeriesItem>> {
                override fun onResponse(call: Call<List<SeriesItem>>, response: Response<List<SeriesItem>>) {
                    allItems.addAll(response.body().orEmpty().map { it.toContentItem() })
                    setLoading(false)
                }
                override fun onFailure(call: Call<List<SeriesItem>>, t: Throwable) { setLoading(false) }
            })
    }

    private fun filter(query: String) {
        if (query.length < 2) {
            adapter.submitList(emptyList())
            return
        }
        val results = allItems.filter { it.name.contains(query, ignoreCase = true) }.take(200)
        adapter.submitList(results)
    }

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
}
