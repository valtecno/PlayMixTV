package com.miiptv.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.miiptv.app.api.LiveStream
import com.miiptv.app.api.Session
import com.miiptv.app.databinding.ActivityMultiscreenBinding
import com.miiptv.app.util.PlayerFactory
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * Muestra hasta 4 canales en vivo a la vez. Tocar un recuadro deja ese canal
 * con el audio activado (el resto queda en mute). Mantener presionado permite
 * elegir qué canal va en ese recuadro.
 */
class MultiScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMultiscreenBinding
    private val players = arrayOfNulls<ExoPlayer>(4)
    private var channels: List<LiveStream> = emptyList()
    private var activeSlot = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnExit.setOnClickListener { finish() }

        val playerViews = listOf(binding.player0, binding.player1, binding.player2, binding.player3)
        val slots = listOf(binding.slot0, binding.slot1, binding.slot2, binding.slot3)

        for (i in 0..3) {
            val exo = PlayerFactory.build(this, handleAudioFocus = false)
            playerViews[i].player = exo
            exo.volume = if (i == activeSlot) 1f else 0f
            players[i] = exo

            slots[i].setOnClickListener { setActiveSlot(i) }
            slots[i].setOnLongClickListener { showChannelPicker(i); true }
        }

        loadChannels()
    }

    private fun loadChannels() {
        Session.api(this).getLiveStreams(Session.username(this), Session.password(this))
            .enqueue(object : Callback<List<LiveStream>> {
                override fun onResponse(call: Call<List<LiveStream>>, response: Response<List<LiveStream>>) {
                    channels = response.body().orEmpty()
                    // Precarga los primeros 4 canales automáticamente
                    channels.take(4).forEachIndexed { index, ch -> loadIntoSlot(index, ch) }
                    if (channels.isEmpty()) {
                        Toast.makeText(this@MultiScreenActivity, "No hay canales disponibles", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(call: Call<List<LiveStream>>, t: Throwable) {
                    Toast.makeText(this@MultiScreenActivity, "Error: ${t.message}", Toast.LENGTH_LONG).show()
                }
            })
    }

    private fun showChannelPicker(slot: Int) {
        if (channels.isEmpty()) return
        val names = channels.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Elegir canal")
            .setItems(names) { _, which -> loadIntoSlot(slot, channels[which]) }
            .show()
    }

    private fun loadIntoSlot(slot: Int, channel: LiveStream) {
        val url = Session.liveStreamUrl(this, channel.streamId)
        players[slot]?.apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    private fun setActiveSlot(slot: Int) {
        activeSlot = slot
        players.forEachIndexed { index, exo -> exo?.volume = if (index == slot) 1f else 0f }
    }

    override fun onStop() {
        super.onStop()
        players.forEach { it?.release() }
    }
}
