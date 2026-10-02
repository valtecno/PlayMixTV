package com.miiptv.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import com.miiptv.app.util.KidsMode
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
 * Ícono papelera → elimina el perfil (con confirmación).
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

        binding.tvTvHint.visibility = View.VISIBLE

        if (intent.getBooleanExtra(EXTRA_EDIT_ACTIVE, false)) {
            val activo = Profiles.active(this)
            if (activo != null && !activo.isKids) showEditDialog(activo)
        }
    }

    private fun setupGrid() {
        val profiles = Profiles.getAll(this)
        val canAdd   = profiles.size < 6

        adapter = ProfileAdapter(
            profiles  = profiles,
            canAdd    = canAdd,
            onSelect  = { profile -> selectProfile(profile) },
            onEdit    = { profile -> showEditDialog(profile) },
            onDelete  = { profile -> confirmDelete(profile) },
            onAddNew  = { showAddDialog() }
        )

        val cols = if (resources.displayMetrics.widthPixels >= 1200) 4 else 2
        binding.rvProfiles.layoutManager = GridLayoutManager(this, cols)
        binding.rvProfiles.adapter = adapter

        val anim = AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_down)
        binding.rvProfiles.layoutAnimation = anim
    }

    private fun refreshGrid() {
        val profiles = Profiles.getAll(this)
        val canAdd   = profiles.size < 6
        adapter.updateAll(profiles, canAdd)
    }

    private fun selectProfile(profile: Profiles.Profile) {
        Profiles.setActive(this, profile)
        KidsMode.setActive(this, profile.isKids)
        DataSync.restore(this) { }

        val fromSettings = intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)
        if (fromSettings) {
            finish()
        } else {
            val destino = if (WelcomeActivity.debesMostrar(this))
                WelcomeActivity::class.java else MainActivity::class.java
            startActivity(Intent(this, destino))
            finish()
        }
    }

    private fun showEditDialog(profile: Profiles.Profile) {
        if (profile.isKids) return

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_profile_edit, null)
        val tvEmoji    = dialogView.findViewById<TextView>(R.id.tvEditAvatarEmoji)
        val vCircle    = dialogView.findViewById<View>(R.id.vEditAvatarCircle)
        val rvAvatars  = dialogView.findViewById<RecyclerView>(R.id.rvAvatarPicker)
        val etName     = dialogView.findViewById<TextInputEditText>(R.id.etProfileName)

        var selectedAvatar = profile.avatar

        fun refreshAvatar(av: Avatar) {
            selectedAvatar = av
            // Mostrar letra del avatar centrada
            tvEmoji.text = av.emoji
            (vCircle.background as? GradientDrawable)?.setColor(av.color)
                ?: vCircle.background?.setTint(av.color)
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
        if (Profiles.getAll(this).size >= 6) {
            Toast.makeText(this, R.string.profile_max_reached, Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_profile_edit, null)
        val tvEmoji    = dialogView.findViewById<TextView>(R.id.tvEditAvatarEmoji)
        val vCircle    = dialogView.findViewById<View>(R.id.vEditAvatarCircle)
        val rvAvatars  = dialogView.findViewById<RecyclerView>(R.id.rvAvatarPicker)
        val etName     = dialogView.findViewById<TextInputEditText>(R.id.etProfileName)

        // Avatar inicial por defecto (el A2 — un color distinto al de los perfiles ya creados)
        var selectedAvatar: Avatar = Avatar.A2

        fun refreshAvatar(av: Avatar) {
            selectedAvatar = av
            tvEmoji.text = av.emoji
            (vCircle.background as? GradientDrawable)?.setColor(av.color)
                ?: vCircle.background?.setTint(av.color)
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

    private fun confirmDelete(profile: Profiles.Profile) {
        AlertDialog.Builder(this, R.style.AppDialog)
            .setMessage(getString(R.string.profile_delete_confirm, profile.name))
            .setPositiveButton(R.string.profile_delete) { _, _ ->
                val ok = Profiles.delete(this, profile)
                if (ok) {
                    Toast.makeText(this, R.string.profile_deleted, Toast.LENGTH_SHORT).show()
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
        private val onDelete:  (Profiles.Profile) -> Unit,
        private val onAddNew:  () -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var items: List<Profiles.Profile?> = buildItems(profiles, canAdd)

        private fun buildItems(profiles: List<Profiles.Profile>, canAdd: Boolean): List<Profiles.Profile?> {
            // null = tarjeta "Agregar perfil"
            return if (canAdd) profiles + listOf(null) else profiles
        }

        companion object {
            private const val TYPE_PROFILE = 0
            private const val TYPE_ADD     = 1
        }

        override fun getItemViewType(position: Int) =
            if (items[position] == null) TYPE_ADD else TYPE_PROFILE

        // --- ViewHolder para perfiles normales ---
        inner class ProfileVH(view: View) : RecyclerView.ViewHolder(view) {
            val flBg:      FrameLayout = view.findViewById(R.id.flAvatarBg)
            val vCircle:   View        = view.findViewById(R.id.vAvatarCircle)
            val tvEmoji:   TextView    = view.findViewById(R.id.tvAvatarEmoji)
            val tvName:    TextView    = view.findViewById(R.id.tvProfileName)
            val ivEdit:    ImageView   = view.findViewById(R.id.ivEditOverlay)
            val ivBadge:   ImageView   = view.findViewById(R.id.ivEditBadge)
            val ivDelete:  ImageView   = view.findViewById(R.id.ivDeleteBadge)
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
            return if (viewType == TYPE_PROFILE) ProfileVH(view) else AddVH(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val profile = items[position]

            if (holder is AddVH && profile == null) {
                // Tarjeta de "Agregar perfil"
                holder.tvEmoji.text = "+"
                holder.tvEmoji.textSize = 42f
                holder.tvName.text = getString(R.string.profile_add)
                holder.tvName.setTextColor(0xFFAAAAAA.toInt())
                // Círculo gris con borde punteado visual
                (holder.vCircle.background as? GradientDrawable)?.let {
                    it.setColor(0xFF2A2A2A.toInt())
                    it.setStroke(3, 0xFF666666.toInt())
                } ?: holder.vCircle.background?.setTint(0xFF2A2A2A.toInt())

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
            holder.tvEmoji.text  = avatar.emoji
            // Para avatares de letras, ajustar tamaño de texto
            holder.tvEmoji.textSize = if (avatar == Avatar.KIDS) 54f else 42f
            holder.tvName.text   = profile.name

            val bg = holder.vCircle.background?.mutate() as? GradientDrawable
            bg?.setColor(avatar.color) ?: holder.vCircle.background?.setTint(avatar.color)

            holder.itemView.setOnClickListener { onSelect(profile) }

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                holder.tvName.setTextColor(
                    if (hasFocus) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt()
                )
            }

            // Badge de editar y papelera — no aplica a Niños
            val nonKidsProfiles = items.filterNotNull().filter { !it.isKids }
            val canDelete = !profile.isKids && nonKidsProfiles.size > 1

            holder.ivBadge.visibility  = if (profile.isKids) View.GONE else View.VISIBLE
            holder.ivDelete.visibility = if (canDelete) View.VISIBLE else View.GONE

            holder.ivBadge.setOnClickListener { onEdit(profile) }
            holder.ivDelete.setOnClickListener { onDelete(profile) }

            if (!profile.isKids) {
                holder.itemView.setOnLongClickListener {
                    onEdit(profile)
                    true
                }
                holder.itemView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_MENU ||
                         keyCode == KeyEvent.KEYCODE_SETTINGS ||
                         (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && event.repeatCount > 0) ||
                         (keyCode == KeyEvent.KEYCODE_ENTER && event.repeatCount > 0))
                    ) {
                        onEdit(profile)
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
            val vCircle:   View     = view.findViewById(R.id.vPickerCircle)
            val tvEmoji:   TextView = view.findViewById(R.id.tvPickerEmoji)
            val vSelected: View     = view.findViewById(R.id.vPickerSelected)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_avatar_picker, parent, false))

        override fun getItemCount() = avatars.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val av = avatars[position]
            holder.tvEmoji.text = av.emoji
            // Letra del avatar en tamaño adecuado para el picker
            holder.tvEmoji.textSize = if (av == Avatar.KIDS) 20f else 15f
            val bg = holder.vCircle.background?.mutate() as? GradientDrawable
            bg?.setColor(av.color) ?: holder.vCircle.background?.setTint(av.color)
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
