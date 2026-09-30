package com.miiptv.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
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
 * El perfil de Niños activa KidsMode automáticamente.
 */
class ProfileSelectorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileSelectorBinding
    private lateinit var adapter: ProfileAdapter

    companion object {
        /** true = viene de Ajustes (el Back vuelve ahí en vez de cerrar la app). */
        private const val EXTRA_FROM_SETTINGS = "from_settings"

        fun start(context: Context, fromSettings: Boolean = false) {
            context.startActivity(
                Intent(context, ProfileSelectorActivity::class.java)
                    .putExtra(EXTRA_FROM_SETTINGS, fromSettings)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileSelectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val fromSettings = intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)
        if (!fromSettings) {
            // Ocultar la barra de acción si la hubiera (pantalla inmersiva)
            supportActionBar?.hide()
        }

        setupGrid()
    }

    private fun setupGrid() {
        val profiles = Profiles.getAll(this)
        adapter = ProfileAdapter(
            profiles = profiles,
            onSelect = { profile -> selectProfile(profile) },
            onEdit   = { profile -> showEditDialog(profile) }
        )

        // Dos columnas en móvil, hasta cuatro en tablet/TV
        val cols = if (resources.displayMetrics.widthPixels >= 1200) 4 else 2
        binding.rvProfiles.layoutManager = GridLayoutManager(this, cols)
        binding.rvProfiles.adapter = adapter

        // Animación de entrada: los ítems caen de arriba
        val anim = AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_down)
        binding.rvProfiles.layoutAnimation = anim
    }

    private fun selectProfile(profile: Profiles.Profile) {
        Profiles.setActive(this, profile)

        // El perfil de niños activa KidsMode; los demás lo desactivan
        KidsMode.setActive(this, profile.isKids)

        // Restaurar la copia de seguridad de este perfil desde la nube
        DataSync.restore(this) { /* mejor esfuerzo: no bloquea */ }

        val fromSettings = intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)
        if (fromSettings) {
            // Vuelve a donde estaba (MainActivity) con el perfil ya cambiado
            finish()
        } else {
            // Primera vez en este arranque: si es la primera instalación, pasa
            // por WelcomeActivity; si no, directo a MainActivity.
            val destino = if (WelcomeActivity.debesMostrar(this))
                WelcomeActivity::class.java else MainActivity::class.java
            startActivity(Intent(this, destino))
            finish()
        }
    }

    private fun showEditDialog(profile: Profiles.Profile) {
        // El perfil de Niños no es editable (nombre fijo, avatar fijo)
        if (profile.isKids) return

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_profile_edit, null)
        val tvEmoji    = dialogView.findViewById<TextView>(R.id.tvEditAvatarEmoji)
        val vCircle    = dialogView.findViewById<View>(R.id.vEditAvatarCircle)
        val rvAvatars  = dialogView.findViewById<RecyclerView>(R.id.rvAvatarPicker)
        val etName     = dialogView.findViewById<TextInputEditText>(R.id.etProfileName)

        var selectedAvatar = profile.avatar

        fun refreshAvatar(av: Avatar) {
            selectedAvatar = av
            tvEmoji.text = av.emoji
            (vCircle.background as? GradientDrawable)?.setColor(av.color)
                ?: vCircle.background?.setTint(av.color)
        }

        // Inicializar con el avatar actual
        refreshAvatar(selectedAvatar)
        etName.setText(profile.name)
        etName.setSelection(profile.name.length)

        // Galería de avatares (excluye KIDS — solo para el perfil de niños)
        val availableAvatars = Avatar.entries.filter { it != Avatar.KIDS }
        val avatarAdapter = AvatarPickerAdapter(
            avatars  = availableAvatars,
            selected = selectedAvatar,
            onPick   = { av -> refreshAvatar(av) }
        )
        rvAvatars.layoutManager = GridLayoutManager(this, 5)
        rvAvatars.adapter = avatarAdapter

        val dialog = AlertDialog.Builder(this, R.style.AppDialog)
            .setView(dialogView)
            .create()

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

    // =========================================================================
    // Adapter de perfiles
    // =========================================================================

    private class ProfileAdapter(
        private var profiles: List<Profiles.Profile>,
        private val onSelect: (Profiles.Profile) -> Unit,
        private val onEdit:   (Profiles.Profile) -> Unit
    ) : RecyclerView.Adapter<ProfileAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val flBg:    FrameLayout = view.findViewById(R.id.flAvatarBg)
            val vCircle: View        = view.findViewById(R.id.vAvatarCircle)
            val tvEmoji: TextView    = view.findViewById(R.id.tvAvatarEmoji)
            val tvName:  TextView    = view.findViewById(R.id.tvProfileName)
            val ivEdit:  ImageView   = view.findViewById(R.id.ivEditOverlay)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false))

        override fun getItemCount() = profiles.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val profile = profiles[position]
            val avatar  = profile.avatar

            holder.tvEmoji.text = avatar.emoji
            holder.tvName.text  = profile.name

            // Color del círculo
            val bg = holder.vCircle.background?.mutate() as? GradientDrawable
            bg?.setColor(avatar.color) ?: holder.vCircle.background?.setTint(avatar.color)

            // Toque corto → seleccionar
            holder.itemView.setOnClickListener { onSelect(profile) }

            // Toque largo → editar (no aplica a Niños)
            if (!profile.isKids) {
                holder.itemView.setOnLongClickListener {
                    holder.ivEdit.visibility = View.VISIBLE
                    holder.itemView.postDelayed({ holder.ivEdit.visibility = View.GONE }, 200)
                    onEdit(profile)
                    true
                }
            }
        }

        fun updateProfile(updated: Profiles.Profile) {
            val idx = profiles.indexOfFirst { it.profileId == updated.profileId }
            if (idx >= 0) {
                profiles = profiles.toMutableList().also { it[idx] = updated }
                notifyItemChanged(idx)
            }
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
