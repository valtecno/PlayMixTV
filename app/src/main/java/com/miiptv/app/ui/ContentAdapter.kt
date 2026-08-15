package com.miiptv.app.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.miiptv.app.R
import com.miiptv.app.api.ContentItem
import com.miiptv.app.databinding.ItemChannelBinding
import com.miiptv.app.databinding.ItemPosterGridBinding
import com.miiptv.app.util.Favorites
import com.miiptv.app.util.Parental
import com.squareup.picasso.Picasso

/**
 * Adapter unificado para canales, películas y series.
 *
 * Tiene dos presentaciones:
 *  - fila (por defecto): logo + nombre, para canales, historial y favoritos.
 *  - póster (grilla): carátula grande, para Películas y Series, con la densidad
 *    de columnas que el usuario elija en "Personalizar".
 */
class ContentAdapter(
    private val onClick: (ContentItem) -> Unit,
    private val onFavoriteToggled: (() -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_ROW = 0
        const val TYPE_POSTER = 1
    }

    private val items = mutableListOf<ContentItem>()

    var posterMode: Boolean = false
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

    override fun getItemViewType(position: Int) = if (posterMode) TYPE_POSTER else TYPE_ROW

    override fun getItemCount() = items.size

    inner class RowHolder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)
    inner class PosterHolder(val binding: ItemPosterGridBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_POSTER) {
            PosterHolder(ItemPosterGridBinding.inflate(inflater, parent, false))
        } else {
            RowHolder(ItemChannelBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val locked = Parental.isCategoryLocked(context, item.categoryId)
        val fav = Favorites.isFavorite(context, item)
        val starRes = if (fav) android.R.drawable.star_big_on else android.R.drawable.star_big_off
        val starTint = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (fav) R.color.star_active else R.color.text_muted)
        )

        when (holder) {
            is RowHolder -> with(holder.binding) {
                tvName.text = item.name
                loadImage(item.icon, ivLogo)
                ivLock.visibility = if (locked) View.VISIBLE else View.GONE
                ivFavorite.setImageResource(starRes)
                ivFavorite.imageTintList = starTint
                ivFavorite.setOnClickListener { toggleFavorite(holder, item) }
                root.setOnClickListener { onClick(item) }
            }

            is PosterHolder -> with(holder.binding) {
                tvName.text = item.name
                loadImage(item.icon, ivPoster)
                ivLock.visibility = if (locked) View.VISIBLE else View.GONE
                ivFavorite.setImageResource(starRes)
                ivFavorite.imageTintList = starTint
                ivFavorite.setOnClickListener { toggleFavorite(holder, item) }
                root.setOnClickListener { onClick(item) }
            }
        }
    }

    private fun loadImage(url: String?, target: android.widget.ImageView) {
        if (!url.isNullOrBlank()) {
            Picasso.get().load(url).into(target)
        } else {
            target.setImageDrawable(null)
        }
    }

    private fun toggleFavorite(holder: RecyclerView.ViewHolder, item: ContentItem) {
        Favorites.toggle(holder.itemView.context, item)
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
        onFavoriteToggled?.invoke()
    }
}
