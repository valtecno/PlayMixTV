package com.miiptv.app.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.miiptv.app.R
import com.miiptv.app.api.ContentItem
import com.miiptv.app.api.ContentType
import com.miiptv.app.databinding.ItemChannelBinding
import com.miiptv.app.databinding.ItemPosterGridBinding
import com.miiptv.app.databinding.ItemSearchResultBinding
import com.miiptv.app.util.Epg
import com.miiptv.app.util.Favorites
import com.miiptv.app.util.Parental
import com.miiptv.app.util.RemoteControl
import com.squareup.picasso.Picasso

/**
 * Adapter unificado para canales, películas y series.
 *
 * Tiene tres presentaciones:
 *  - fila (por defecto): logo + nombre, para canales, PPV, radios, historial y
 *    favoritos.
 *  - póster (grilla): carátula grande, para Películas y Series, con la densidad
 *    de columnas que el usuario elija en "Personalizar".
 *  - búsqueda: fila con miniatura vertical y etiqueta de tipo, porque ahí se
 *    mezclan los tres tipos de contenido en una misma lista.
 *
 * Las dos pasan por [RemoteControl.applyItemFocus], que es lo que hace que en
 * TV se vea sobre qué tarjeta está parado el control remoto.
 */
class ContentAdapter(
    private val onClick: (ContentItem) -> Unit,
    private val onFavoriteToggled: (() -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /**
     * El EPG solo tiene sentido en Canales y PPV: en Radios, Favoritos,
     * Historial y Buscador el adapter reutiliza la misma fila para el mismo
     * ContentType.LIVE, así que sin este freno pediría EPG también ahí. Lo
     * prende/apaga MainActivity según la sección en la que esté parado.
     */
    var epgEnabled: Boolean = false

    private companion object {
        const val TYPE_ROW = 0
        const val TYPE_POSTER = 1
        const val TYPE_SEARCH = 2
    }

    private val items = mutableListOf<ContentItem>()

    var posterMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    /**
     * Manejo con control remoto. Lo fija MainActivity desde
     * [RemoteControl.isEnabled]; acá solo cambia cómo se enfoca y cómo se marca
     * un favorito, no el aspecto en reposo.
     */
    /**
     * Fila de búsqueda: miniatura vertical con la carátula y etiqueta de tipo.
     *
     * La búsqueda mezcla canales, películas y series en una misma lista, así que
     * necesita las dos cosas que la fila normal no da: una imagen con forma de
     * carátula (la de canales es un cuadrado de 48dp pensado para logos, donde
     * un póster se veía diminuto) y decir de qué tipo es cada resultado.
     */
    var searchMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    var remoteMode: Boolean = false
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

    /** Lo que se está mostrando ahora, para armar listas de reproducción. */
    val currentItems: List<ContentItem> get() = items.toList()

    override fun getItemViewType(position: Int) = when {
        posterMode -> TYPE_POSTER
        searchMode -> TYPE_SEARCH
        else -> TYPE_ROW
    }

    override fun getItemCount() = items.size

    inner class RowHolder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)
    inner class PosterHolder(val binding: ItemPosterGridBinding) : RecyclerView.ViewHolder(binding.root)
    inner class SearchHolder(val binding: ItemSearchResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_POSTER -> PosterHolder(ItemPosterGridBinding.inflate(inflater, parent, false))
            TYPE_SEARCH -> SearchHolder(ItemSearchResultBinding.inflate(inflater, parent, false))
            else -> RowHolder(ItemChannelBinding.inflate(inflater, parent, false))
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
                setupFocus(holder, item, root)
                bindEpgNow(tvEpgNow, item)
            }

            is PosterHolder -> with(holder.binding) {
                tvName.text = item.name
                loadImage(item.icon, ivPoster)
                ivLock.visibility = if (locked) View.VISIBLE else View.GONE
                ivFavorite.setImageResource(starRes)
                ivFavorite.imageTintList = starTint
                ivFavorite.setOnClickListener { toggleFavorite(holder, item) }
                root.setOnClickListener { onClick(item) }
                setupFocus(holder, item, root)
            }

            is SearchHolder -> with(holder.binding) {
                tvName.text = item.name
                tvType.setText(
                    when (item.type) {
                        ContentType.MOVIE -> R.string.type_movie
                        ContentType.SERIES -> R.string.type_series
                        else -> R.string.type_live
                    }
                )

                /*
                 * El recorte depende del tipo, y esto importa:
                 *  - Carátula de película o serie: es vertical como el hueco, así
                 *    que centerCrop lo llena entero sin deformar.
                 *  - Logo de canal o emisora: es apaisado o cuadrado. Con
                 *    centerCrop se le comerían los costados y quedaría un trozo
                 *    de logo irreconocible, así que entra completo con fitCenter.
                 */
                ivCover.scaleType =
                    if (item.type == ContentType.MOVIE || item.type == ContentType.SERIES) {
                        android.widget.ImageView.ScaleType.CENTER_CROP
                    } else {
                        android.widget.ImageView.ScaleType.FIT_CENTER
                    }
                // Recorta la imagen con las esquinas redondeadas del marco
                ivCover.clipToOutline = true
                loadImage(item.icon, ivCover)

                ivLock.visibility = if (locked) View.VISIBLE else View.GONE
                ivFavorite.setImageResource(starRes)
                ivFavorite.imageTintList = starTint
                ivFavorite.setOnClickListener { toggleFavorite(holder, item) }
                root.setOnClickListener { onClick(item) }
                setupFocus(holder, item, root)
            }
        }
    }

    /**
     * Resalte de foco y, en TV, forma alternativa de marcar favoritos.
     *
     * Con el remoto la tarjeta se enfoca entera (ver [RemoteControl.applyItemFocus]),
     * así que la estrella deja de ser alcanzable con las flechas. A cambio, la
     * pulsación larga del botón central marca o desmarca el favorito. Sin esto,
     * arreglar la navegación habría dejado a los usuarios de TV sin poder usar
     * favoritos, que es de las funciones más usadas de la app.
     */
    private fun setupFocus(
        holder: RecyclerView.ViewHolder,
        item: ContentItem,
        root: View
    ) {
        RemoteControl.applyItemFocus(root, remoteMode)

        if (remoteMode) {
            root.setOnLongClickListener {
                val ahoraEsFavorito = toggleFavorite(holder, item)
                Toast.makeText(
                    root.context,
                    if (ahoraEsFavorito) R.string.fav_added else R.string.fav_removed,
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
        } else {
            // Las vistas se reciclan: hay que quitarlo explícitamente al volver
            // a modo táctil, o una tarjeta reutilizada conservaría el listener.
            root.setOnLongClickListener(null)
            root.isLongClickable = false
        }
    }

    private fun loadImage(url: String?, target: android.widget.ImageView) {
        if (!url.isNullOrBlank()) {
            Picasso.get().load(url).into(target)
        } else {
            // Cancelar la petición anterior antes de vaciar: la vista viene
            // reciclada y podía tener una descarga a medio camino de OTRO ítem.
            // Sin esto, al terminar esa descarga la imagen aparecía sobre la
            // fila equivocada.
            Picasso.get().cancelRequest(target)
            target.setImageDrawable(null)
        }
    }

    /**
     * Solo canales en vivo tienen programa actual. La respuesta llega async
     * (a veces ya cacheada, a veces recién pedida al panel), y para entonces
     * la fila reciclada puede estar mostrando otro canal — por eso se guarda
     * qué stream_id la pidió y se descarta la respuesta si ya no coincide.
     */
    private fun bindEpgNow(tvEpgNow: android.widget.TextView, item: ContentItem) {
        if (!epgEnabled || item.type != ContentType.LIVE) {
            tvEpgNow.visibility = View.GONE
            return
        }
        tvEpgNow.visibility = View.GONE
        tvEpgNow.tag = item.id
        Epg.nowPlaying(tvEpgNow.context, item.id) { titulo ->
            if (tvEpgNow.tag == item.id) {
                tvEpgNow.text = titulo
                tvEpgNow.visibility = if (titulo.isNullOrBlank()) View.GONE else View.VISIBLE
            }
        }
    }

    /** @return true si el ítem quedó marcado como favorito. */
    private fun toggleFavorite(holder: RecyclerView.ViewHolder, item: ContentItem): Boolean {
        val ahoraEsFavorito = Favorites.toggle(holder.itemView.context, item)
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
        onFavoriteToggled?.invoke()
        return ahoraEsFavorito
    }
}
