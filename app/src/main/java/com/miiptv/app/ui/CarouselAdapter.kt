package com.miiptv.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.miiptv.app.R
import com.miiptv.app.api.ContentItem
import com.miiptv.app.api.ContentType
import com.miiptv.app.databinding.ItemPosterBinding
import com.miiptv.app.util.Appearance
import com.squareup.picasso.Picasso

/**
 * Carrusel horizontal de pósters, usado para la fila de "Novedades"
 * (lo último agregado al servidor) en la pantalla principal.
 */
class CarouselAdapter(
    private val onClick: (ContentItem) -> Unit
) : RecyclerView.Adapter<CarouselAdapter.ViewHolder>() {

    private val items = mutableListOf<ContentItem>()

    /** Ancho de cada tarjeta. Lo calcula AutoCarousel según el espacio disponible. */
    var itemWidth: Int = 0
        set(value) {
            if (field != value && value > 0) {
                field = value
                notifyDataSetChanged()
            }
        }

    /**
     * Título a una sola línea. En móvil las dos secciones del Inicio van
     * apiladas, así que cada fila tiene la mitad de alto: con dos líneas de
     * título más la etiqueta, al póster casi no le quedaba lugar.
     */
    var compact: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    fun submitList(newItems: List<ContentItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemPosterBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        if (itemWidth > 0) {
            holder.binding.root.layoutParams =
                holder.binding.root.layoutParams.apply { width = itemWidth }
        }

        holder.binding.tvPosterName.maxLines = if (compact) 1 else 2
        holder.binding.tvPosterName.text = item.name
        holder.binding.tvPosterTag.setTextColor(Appearance.accent(holder.itemView.context))
        holder.binding.tvPosterTag.setText(
            if (item.type == ContentType.SERIES) R.string.tab_series else R.string.tab_movies
        )

        if (!item.icon.isNullOrBlank()) {
            Picasso.get().load(item.icon).into(holder.binding.ivPoster)
        } else {
            holder.binding.ivPoster.setImageDrawable(null)
        }

        holder.binding.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
