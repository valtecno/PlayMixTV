package com.miiptv.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.miiptv.app.R
import com.miiptv.app.databinding.ActivityHelpBinding
import com.miiptv.app.databinding.ItemCategoryBinding
import com.miiptv.app.databinding.ItemHelpBinding
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.util.HelpContent
import com.miiptv.app.util.RemoteControl

/**
 * Asistente de ayuda: Vanessa responde cómo se usa la app.
 *
 * Son preguntas frecuentes con un buscador (ver [HelpContent]): se escribe la
 * duda con palabras propias ("no se escucha", "cómo pongo subtítulos") y se
 * muestran primero las respuestas que mejor calzan, con la más probable ya
 * abierta. También se puede recorrer por tema sin escribir nada, que es lo
 * cómodo con control remoto. Todo funciona sin internet.
 *
 * Si la respuesta no está, abajo hay un botón directo a soporte por WhatsApp.
 */
class HelpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpBinding
    private lateinit var adapter: HelpAdapter
    private var temaElegido: HelpContent.Tema? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Foto de Vanessa recortada en círculo (el fondo bg_avatar es un óvalo)
        binding.ivVanessa.outlineProvider = ViewOutlineProvider.BACKGROUND
        binding.ivVanessa.clipToOutline = true

        val remoto = RemoteControl.isEnabled(this)
        adapter = HelpAdapter(remoto)
        binding.recyclerHelp.layoutManager = LinearLayoutManager(this)
        binding.recyclerHelp.adapter = adapter
        // Sin la animación de "cambio": al abrir una respuesta, esa animación
        // reemplaza la fila por una copia y el control remoto perdía el foco.
        (binding.recyclerHelp.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        renderTemas()
        binding.etHelpSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = aplicar()
        })

        binding.btnHelpWhatsapp.setOnClickListener { abrirWhatsapp() }
        if (remoto) {
            binding.btnHelpWhatsapp.background = Appearance.withFocusState(
                this, binding.btnHelpWhatsapp.background!!, 12f
            )
        }

        aplicar()

        // Con control remoto se arranca en el primer tema: escribir con el
        // mando es lento, así que lo natural es recorrer por tema. El
        // buscador queda justo arriba, a una flecha de distancia.
        if (remoto) RemoteControl.focusWhenReady(binding.helpTopicContainer.getChildAt(0))
    }

    private fun renderTemas() {
        binding.helpTopicContainer.removeAllViews()
        val opciones: List<HelpContent.Tema?> = listOf<HelpContent.Tema?>(null) + HelpContent.Tema.values()
        opciones.forEach { tema ->
            val chip: TextView =
                ItemCategoryBinding.inflate(layoutInflater, binding.helpTopicContainer, false).root
            chip.text = tema?.etiqueta ?: getString(R.string.help_topic_all)
            chip.tag = tema
            Appearance.applyChipState(chip, tema == temaElegido)
            chip.setOnClickListener {
                temaElegido = tema
                // Se repintan los chips que ya están: armarlos de nuevo
                // destruiría el que tiene el foco del control remoto.
                for (i in 0 until binding.helpTopicContainer.childCount) {
                    val c = binding.helpTopicContainer.getChildAt(i) as? TextView ?: continue
                    Appearance.applyChipState(c, c.tag == temaElegido)
                }
                aplicar()
            }
            binding.helpTopicContainer.addView(chip)
        }
    }

    /** Recalcula la lista según lo escrito y el tema elegido. */
    private fun aplicar() {
        val consulta = binding.etHelpSearch.text?.toString().orEmpty().trim()
        val resultados = HelpContent.buscar(consulta, temaElegido)
        // Buscando, la respuesta más probable se muestra ya abierta: así la
        // duda se contesta sin un toque más. Recorriendo por tema, todo cerrado.
        adapter.mostrar(resultados, abrirPrimera = consulta.isNotEmpty())
        binding.recyclerHelp.scrollToPosition(0)
        binding.tvHelpEmpty.visibility = if (resultados.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun abrirWhatsapp() {
        val mensaje = getString(R.string.help_whatsapp_message)
        val url = "https://wa.me/${HelpContent.WHATSAPP_SOPORTE}?text=" + Uri.encode(mensaje)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.contact_no_whatsapp, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- Lista de preguntas ----------------

    private class HelpAdapter(private val remoto: Boolean) : RecyclerView.Adapter<HelpAdapter.VH>() {

        class VH(val b: ItemHelpBinding) : RecyclerView.ViewHolder(b.root)

        private val items = ArrayList<HelpContent.Pregunta>()
        /** Preguntas abiertas, por su texto (sobrevive a que la lista se reordene). */
        private val abiertas = HashSet<String>()

        fun mostrar(nuevas: List<HelpContent.Pregunta>, abrirPrimera: Boolean) {
            items.clear()
            items.addAll(nuevas)
            abiertas.clear()
            if (abrirPrimera) nuevas.firstOrNull()?.let { abiertas.add(it.pregunta) }
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemHelpBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            RemoteControl.applyItemFocus(b.root, remoto)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = items[position]
            val abierta = p.pregunta in abiertas
            with(holder.b) {
                tvHelpTopic.text = p.tema.etiqueta.uppercase()
                tvHelpQuestion.text = p.pregunta
                tvHelpAnswer.text = p.respuesta
                tvHelpAnswer.visibility = if (abierta) View.VISIBLE else View.GONE
                tvHelpChevron.rotation = if (abierta) 90f else 0f
                root.setOnClickListener {
                    if (!abiertas.remove(p.pregunta)) abiertas.add(p.pregunta)
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                }
            }
        }
    }
}
