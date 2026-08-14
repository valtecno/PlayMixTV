package com.miiptv.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.miiptv.app.R
import com.miiptv.app.api.ContentItem
import com.miiptv.app.databinding.ItemChannelBinding
import com.miiptv.app.util.Favorites
import com.miiptv.app.util.Parental
import com.squareup.picasso.Picasso

class ContentAdapter(
    private val onClick: (ContentItem) -> Unit,
    private val onFavoriteToggled: (() -> Unit)? = null
) : RecyclerView.Adapter<ContentAdapter.ViewHolder>() {

    private val items = mutableListOf<ContentItem>()

    fun submitList(newItems: List<ContentItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        holder.binding.tvName.text = item.name

        if (!item.icon.isNullOrBlank()) {
            Picasso.get().load(item.icon).into(holder.binding.ivLogo)
        } else {
            holder.binding.ivLogo.setImageDrawable(null)
        }

        holder.binding.ivLock.visibility =
            if (Parental.isCategoryLocked(context, item.categoryId)) android.view.View.VISIBLE else android.view.View.GONE

        val fav = Favorites.isFavorite(context, item)
        holder.binding.ivFavorite.setImageResource(
            if (fav) android.R.drawable.star_big_on else android.R.drawable.star_big_off
        )
        val starColor = if (fav) R.color.star_active else R.color.text_muted
        holder.binding.ivFavorite.imageTintList =
            android.content.res.ColorStateList.valueOf(context.getColor(starColor))
        holder.binding.ivFavorite.setOnClickListener {
            Favorites.toggle(context, item)
            notifyItemChanged(position)
            onFavoriteToggled?.invoke()
        }

        holder.binding.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size
}
