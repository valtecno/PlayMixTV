package com.miiptv.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.miiptv.app.R
import com.miiptv.app.databinding.ActivityProfileSelectorBinding
import com.miiptv.app.util.DataSync
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.util.KidsMode
import com.miiptv.app.util.ProfileLayout
import com.miiptv.app.util.Profiles
import com.miiptv.app.util.Profiles.Avatar
import com.miiptv.app.ui.WelcomeActivity

/**
 * Pantalla "¿Quién está viendo?" — selector de perfiles estilo Netflix.
 *
 * Se muestra:
 *  - Después del login (LoginActivity → ProfileSelectorActivity → MainActivity)
 *  - Al inicio de cada sesión si el usuario tiene más de un perfil (SplashActivity)
 *  - Cuando el usuario elige "Cambiar perfil" desde Ajustes
 *
 * Toque corto → selecciona el perfil y navega a MainActivity.
 * Toque largo → abre el diálogo de edición (nombre + avatar).
 * Mantener presionado → Editar u Ocultar perfil (ocultar no borra nada).
 * Tarjeta "+" → crea un perfil nuevo.
 * El perfil de Niños activa KidsMode automáticamente.
 */
class ProfileSelectorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileSelectorBinding
    private lateinit var adapter: ProfileAdapter

    companion object {
        /** true = viene de Ajustes (el Back vuelve ahí en vez de cerrar la app). */
        private const val EXTRA_FROM_SETTINGS = "from_settings"
        /** true = abre directo el diálogo de edición del perfil activo. */
        private const val EXTRA_EDIT_ACTIVE = "edit_active"

        fun start(context: Context, fromSettings: Boolean = false) {
            context.startActivity(
                Intent(context, ProfileSelectorActivity::class.java)
                    .putExtra(EXTRA_FROM_SETTINGS, fromSettings)
            )
        }

        /** Abre el editor (nombre + avatar) del perfil activo desde Ajustes. */
        fun startEditActive(context: Context) {
            context.startActivity(
                Intent(context, ProfileSelectorActivity::class.java)
                    .putExtra(EXTRA_FROM_SETTINGS, true)
                    .putExtra(EXTRA_EDIT_ACTIVE, true)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileSelectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val fromSettings = intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)
        if (!fromSettings) {
            supportActionBar?.hide()
        }

        setupGrid()

        binding.tvShowHidden.setOnClickListener {
            Profiles.unhideAll(this)
            refreshGrid()
        }
        updateHiddenLink()

        binding.tvTvHint.setText(R.string.profile_hint_mobile)
        binding.tvTvHint.visibility = View.VISIBLE

        if (intent.getBooleanExtra(EXTRA_EDIT_ACTIVE, false)) {
            val activo = Profiles.active(this)
            if (activo != null && !activo.isKids) showEditDialog(activo)
        }
    }

    private fun setupGrid() {
        val profiles = visibleProfiles()
        val canAdd   = Profiles.canAdd(profiles.size)

        adapter = ProfileAdapter(
            profiles  = profiles,
            canAdd    = canAdd,
            onSelect  = { profile -> selectProfile(profile) },
            onEdit    = { profile -> showEditDialog(profile) },
            onAddNew  = { showAddDialog() }
        )

        binding.rvProfiles.layoutManager = buildLayoutManager()
        binding.rvProfiles.adapter = adapter

        val anim = AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_down)
        binding.rvProfiles.layoutAnimation = anim
    }

    private fun refreshGrid() {
        val profiles = visibleProfiles()
        val canAdd   = Profiles.canAdd(profiles.size)
        adapter.updateAll(profiles, canAdd)
        binding.rvProfiles.layoutManager = buildLayoutManager()
        updateHiddenLink()
    }

    /** Perfiles a mostrar: los ocultos en este dispositivo no aparecen (en ningún equipo se borra nada). */
    private fun visibleProfiles(): List<Profiles.Profile> = Profiles.getVisible(this)

    private fun updateHiddenLink() {
        val n = Profiles.hiddenIds(this).size
        binding.tvShowHidden.visibility = if (n > 0) View.VISIBLE else View.GONE
        binding.tvShowHidden.text = getString(R.string.profile_show_hidden, n)
    }

    private fun isMobile() = !DeviceMode.isTv(this)

    /**
     * TV  → fila única, máximo 4 perfiles por fila.
     * Móvil → 2 columnas centradas; si el último ítem queda solo (p. ej. el "+")
     * ocupa toda la fila y queda centrado debajo de los demás.
     */
    private fun buildLayoutManager(): GridLayoutManager {
        val total = adapter.itemCount.coerceAtLeast(1)
        if (!isMobile()) return GridLayoutManager(this, total.coerceIn(1, 4))
        return GridLayoutManager(this, 2).also { lm ->
            lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    ProfileLayout.spanSize(position, adapter.itemCount)
            }
        }
    }

    /** Diámetro del círculo en móvil: se encoge según la cantidad de perfiles para que todos quepan. */
    private fun mobileAvatarPx(): Int {
        val dm = resources.displayMetrics
        return ProfileLayout.avatarSizePx(adapter.itemCount, dm.widthPixels, dm.heightPixels, dm.density)
    }

    private fun applyMobileSize(itemView: View) {
        if (!isMobile()) return
        val size = mobileAvatarPx()
        val d = resources.displayMetrics.density
        itemView.findViewById<View>(R.id.flAvatarBg).layoutParams =
            (itemView.findViewById<View>(R.id.flAvatarBg).layoutParams).also { it.width = size; it.height = size }
        itemView.findViewById<TextView>(R.id.tvProfileName).let { tv ->
            tv.layoutParams = tv.layoutParams.also { it.width = (size + 24 * d).toInt() }
            tv.textSize = if (size < 90 * d) 12f else 16f
        }
        itemView.findViewById<TextView>(R.id.tvAvatarEmoji).textSize = size / d * 0.4f
    }

    private fun selectProfile(profile: Profiles.Profile) {
        Profiles.setActive(this, profile)
        KidsMode.setActive(this, profile.isKids)
        DataSync.restore(this) { }

        val fromSettings = intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)
        if (fromSettings) {
            finish()
        } else {
            // Intro con sonido y luego Bienvenida (solo la primera vez) o la app
            IntroActivity.start(this, WelcomeActivity.debesMostrar(this))
            finish()
        }
    }

    /** Carga el drawable del avatar en un ImageView + TextView de fallback. */
    private fun bindAvatar(
        ivImage: ImageView,
        tvEmoji: TextView,
        vCircle: View,
        av: Avatar
    ) {
        val resId = if (av.drawableRes != null)
            resources.getIdentifier(av.drawableRes, "drawable", packageName)
        else 0

        if (resId != 0) {
            ivImage.visibility = View.VISIBLE
            tvEmoji.visibility = View.GONE
            ivImage.setImageResource(resId)
            // Fondo sin color para no tapar la imagen (la imagen ya incluye fondo)
            vCircle.background?.setTint(av.color)
        } else {
            ivImage.visibility = View.GONE
            tvEmoji.visibility = View.VISIBLE
            tvEmoji.text = av.emoji
            (vCircle.background as? GradientDrawable)?.setColor(av.color)
                ?: vCircle.background?.setTint(av.color)
        }
    }

    private fun showEditDialog(profile: Profiles.Profile) {
        if (profile.isKids) return

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_profile_edit, null)
        val ivImage    = dialogView.findViewById<ImageView>(R.id.ivEditAvatarImage)
        val tvEmoji    = dialogView.findViewById<TextView>(R.id.tvEditAvatarEmoji)
        val vCircle    = dialogView.findViewById<View>(R.id.vEditAvatarCircle)
        val rvAvatars  = dialogView.findViewById<RecyclerView>(R.id.rvAvatarPicker)
        val etName     = dialogView.findViewById<TextInputEditText>(R.id.etProfileName)

        var selectedAvatar = profile.avatar

        fun refreshAvatar(av: Avatar) {
            selectedAvatar = av
            bindAvatar(ivImage, tvEmoji, vCircle, av)
        }

        refreshAvatar(selectedAvatar)
        etName.setText(profile.name)
        etName.setSelection(profile.name.length)

        val availableAvatars = Avatar.entries.filter { it != Avatar.KIDS }
        val avatarAdapter = AvatarPickerAdapter(
            avatars  = availableAvatars,
            selected = selectedAvatar,
            onPick   = { av -> refreshAvatar(av) }
        )
        rvAvatars.layoutManager = GridLayoutManager(this, 6)
        rvAvatars.adapter = avatarAdapter

        val dialog = AlertDialog.Builder(this, R.style.AppDialog)
            .setView(dialogView)
            .create()

        if (intent.getBooleanExtra(EXTRA_EDIT_ACTIVE, false)) {
            dialog.setOnDismissListener { finish() }
        }

        dialogView.findViewById<View>(R.id.btnCancelEdit).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<View>(R.id.btnSaveEdit).setOnClickListener {
            val nombre = etName.text?.toString()?.trim()?.ifBlank { profile.name } ?: profile.name
            val updated = profile.copy(name = nombre, avatarId = selectedAvatar.id)
            Profiles.update(this, updated)
            adapter.updateProfile(updated)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showAddDialog() {
        if (!Profiles.canAdd(visibleProfiles().size)) {
            Toast.makeText(this, R.string.profile_max_reached, Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_profile_edit, null)
        val ivImage    = dialogView.findViewById<ImageView>(R.id.ivEditAvatarImage)
        val tvEmoji    = dialogView.findViewById<TextView>(R.id.tvEditAvatarEmoji)
        val vCircle    = dialogView.findViewById<View>(R.id.vEditAvatarCircle)
        val rvAvatars  = dialogView.findViewById<RecyclerView>(R.id.rvAvatarPicker)
        val etName     = dialogView.findViewById<TextInputEditText>(R.id.etProfileName)

        // Avatar inicial por defecto (el A2 — un color distinto al de los perfiles ya creados)
        var selectedAvatar: Avatar = Avatar.A2

        fun refreshAvatar(av: Avatar) {
            selectedAvatar = av
            bindAvatar(ivImage, tvEmoji, vCircle, av)
        }

        refreshAvatar(selectedAvatar)
        etName.hint = getString(R.string.profile_default_new_name)

        val availableAvatars = Avatar.entries.filter { it != Avatar.KIDS }
        val avatarAdapter = AvatarPickerAdapter(
            avatars  = availableAvatars,
            selected = selectedAvatar,
            onPick   = { av -> refreshAvatar(av) }
        )
        rvAvatars.layoutManager = GridLayoutManager(this, 6)
        rvAvatars.adapter = avatarAdapter

        val dialog = AlertDialog.Builder(this, R.style.AppDialog)
            .setTitle(getString(R.string.profile_add_title))
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.btnCancelEdit).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<View>(R.id.btnSaveEdit).setOnClickListener {
            val nombre = etName.text?.toString()?.trim()
                ?.ifBlank { getString(R.string.profile_default_new_name) }
                ?: getString(R.string.profile_default_new_name)
            val newProfile = Profiles.add(this, nombre, selectedAvatar.id)
            if (newProfile != null) {
                dialog.dismiss()
                refreshGrid()
            }
        }

        dialog.show()
    }

    /**
     * En modo TV: menú contextual que aparece al mantener pulsado un perfil.
     * Ofrece "Editar perfil" y, si el perfil puede borrarse, "Eliminar perfil".
     * Reemplaza los badges de lápiz y papelera que no son navegables con mando.
     */
    private fun showTvProfileMenu(profile: Profiles.Profile, canDelete: Boolean) {
        val opciones = mutableListOf(getString(R.string.profile_edit_title))
        if (canDelete) opciones.add(getString(R.string.profile_hide))

        AlertDialog.Builder(this, R.style.AppDialog)
            .setTitle(profile.name)
            .setItems(opciones.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showEditDialog(profile)
                    1 -> confirmHide(profile)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Solo oculta el perfil en este dispositivo; no borra el perfil, sus favoritos ni su historial. */
    private fun confirmHide(profile: Profiles.Profile) {
        AlertDialog.Builder(this, R.style.AppDialog)
            .setMessage(getString(R.string.profile_hide_confirm, profile.name))
            .setPositiveButton(R.string.profile_hide) { _, _ ->
                if (Profiles.hide(this, profile)) {
                    Toast.makeText(this, R.string.profile_hidden, Toast.LENGTH_SHORT).show()
                    refreshGrid()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // =========================================================================
    // Adapter de perfiles
    // =========================================================================

    private inner class ProfileAdapter(
        profiles: List<Profiles.Profile>,
        canAdd:   Boolean,
        private val onSelect:  (Profiles.Profile) -> Unit,
        private val onEdit:    (Profiles.Profile) -> Unit,
        private val onAddNew:  () -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<Profiles.Profile?> = buildItems(profiles, canAdd)

        private fun buildItems(profiles: List<Profiles.Profile>, canAdd: Boolean): List<Profiles.Profile?> {
            // null = tarjeta "Agregar perfil"
            return if (canAdd) profiles + listOf(null) else profiles
        }

        override fun getItemViewType(position: Int) =
            if (items[position] == null) 1 else 0

        // --- ViewHolder para perfiles normales ---
        inner class ProfileVH(view: View) : RecyclerView.ViewHolder(view) {
            val flBg:      FrameLayout = view.findViewById(R.id.flAvatarBg)
            val vCircle:   View        = view.findViewById(R.id.vAvatarCircle)
            val ivImage:   ImageView   = view.findViewById(R.id.ivAvatarImage)
            val tvEmoji:   TextView    = view.findViewById(R.id.tvAvatarEmoji)
            val tvName:    TextView    = view.findViewById(R.id.tvProfileName)
            val ivEdit:    ImageView   = view.findViewById(R.id.ivEditOverlay)
        }

        // --- ViewHolder para la tarjeta "+" ---
        inner class AddVH(view: View) : RecyclerView.ViewHolder(view) {
            val tvEmoji: TextView  = view.findViewById(R.id.tvAvatarEmoji)
            val tvName:  TextView  = view.findViewById(R.id.tvProfileName)
            val vCircle: View      = view.findViewById(R.id.vAvatarCircle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_profile, parent, false)
            return if (viewType == 0) ProfileVH(view) else AddVH(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val profile = items[position]

            applyMobileSize(holder.itemView)

            if (holder is AddVH && profile == null) {
                // Tarjeta de "Agregar perfil"
                holder.tvEmoji.visibility = View.VISIBLE
                holder.tvEmoji.text = "+"
                if (!isMobile()) holder.tvEmoji.textSize = 42f
                holder.tvName.text = getString(R.string.profile_add)
                holder.tvName.setTextColor(0xFFAAAAAA.toInt())
                // Círculo semi-transparente sin color de fondo
                (holder.vCircle.background?.mutate() as? GradientDrawable)?.let {
                    it.setColor(0x22FFFFFF.toInt())   // blanco muy tenue, casi invisible
                    it.setStroke(2, 0x55FFFFFF.toInt())  // borde blanco suave
                } ?: holder.vCircle.background?.mutate()?.setTint(0x22FFFFFF.toInt())

                holder.itemView.setOnClickListener { onAddNew() }
                holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                    holder.tvName.setTextColor(
                        if (hasFocus) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
                    )
                }
                return
            }

            if (holder !is ProfileVH || profile == null) return

            val avatar = profile.avatar
            holder.tvName.text = profile.name

            // Mostrar imagen o emoji según el tipo de avatar
            bindAvatar(holder.ivImage, holder.tvEmoji, holder.vCircle, avatar)

            val bg = holder.vCircle.background?.mutate() as? GradientDrawable
            bg?.setColor(avatar.color) ?: holder.vCircle.background?.setTint(avatar.color)

            holder.itemView.setOnClickListener { onSelect(profile) }

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                holder.tvName.setTextColor(
                    if (hasFocus) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt()
                )
            }

            val canDelete = Profiles.canRemove(profile, items.filterNotNull())

            if (!profile.isKids) {
                holder.itemView.setOnLongClickListener {
                    // Mantener presionado: menú con Editar y (si aplica) Eliminar
                    showTvProfileMenu(profile, canDelete)
                    true
                }
                holder.itemView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_MENU ||
                         keyCode == KeyEvent.KEYCODE_SETTINGS ||
                         (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.repeatCount > 0) ||
                         (keyCode == KeyEvent.KEYCODE_ENTER && event.repeatCount > 0))
                    ) {
                        showTvProfileMenu(profile, canDelete)
                        true
                    } else {
                        false
                    }
                }
            } else {
                holder.itemView.setOnLongClickListener(null)
                holder.itemView.setOnKeyListener(null)
            }
        }

        fun updateProfile(updated: Profiles.Profile) {
            val idx = items.indexOfFirst { it?.profileId == updated.profileId }
            if (idx >= 0) {
                items = items.toMutableList().also { it[idx] = updated }
                notifyItemChanged(idx)
            }
        }

        fun updateAll(profiles: List<Profiles.Profile>, canAdd: Boolean) {
            items = buildItems(profiles, canAdd)
            notifyDataSetChanged()
        }
    }

    // =========================================================================
    // Adapter del picker de avatares (en el diálogo de edición)
    // =========================================================================

    private class AvatarPickerAdapter(
        private val avatars:  List<Avatar>,
        selected: Avatar,
        private val onPick: (Avatar) -> Unit
    ) : RecyclerView.Adapter<AvatarPickerAdapter.VH>() {

        private var selectedId = selected.id

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val vCircle:   View      = view.findViewById(R.id.vPickerCircle)
            val ivImage:   ImageView = view.findViewById(R.id.ivPickerImage)
            val tvEmoji:   TextView  = view.findViewById(R.id.tvPickerEmoji)
            val vSelected: View      = view.findViewById(R.id.vPickerSelected)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_avatar_picker, parent, false))

        override fun getItemCount() = avatars.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val av = avatars[position]
            val ctx = holder.itemView.context
            val bg = holder.vCircle.background?.mutate() as? GradientDrawable
            bg?.setColor(av.color) ?: holder.vCircle.background?.setTint(av.color)

            // Mostrar imagen o emoji según disponibilidad
            val resId = if (av.drawableRes != null)
                ctx.resources.getIdentifier(av.drawableRes, "drawable", ctx.packageName)
            else 0

            if (resId != 0) {
                holder.ivImage.visibility = View.VISIBLE
                holder.tvEmoji.visibility = View.GONE
                holder.ivImage.setImageResource(resId)
            } else {
                holder.ivImage.visibility = View.GONE
                holder.tvEmoji.visibility = View.VISIBLE
                holder.tvEmoji.text = av.emoji
                holder.tvEmoji.textSize = if (av == Avatar.KIDS) 20f else 15f
            }

            holder.vSelected.visibility = if (av.id == selectedId) View.VISIBLE else View.GONE

            holder.itemView.setOnClickListener {
                val prev = selectedId
                selectedId = av.id
                val prevIdx = avatars.indexOfFirst { it.id == prev }
                if (prevIdx >= 0) notifyItemChanged(prevIdx)
                notifyItemChanged(position)
                onPick(av)
            }
        }
    }
}
