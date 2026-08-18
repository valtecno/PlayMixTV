package com.miiptv.app.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.miiptv.app.R
import com.miiptv.app.databinding.ActivityPersonalizeBinding
import com.miiptv.app.util.Appearance
import com.miiptv.app.util.RemoteControl
import com.miiptv.app.util.DeviceMode

/**
 * Pantalla "Personalizar": tema de color, tamaño de subtítulos, qué pasa al tocar
 * una película, densidad de las grillas y el ícono del futuro perfil de niños.
 */
class PersonalizeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersonalizeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
        binding = ActivityPersonalizeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        buildColorSwatches()
        buildSubtitleControls()
        buildMovieClickOptions()
        buildGridOptions()

        refreshAll()

        // Mismo problema que en Cuenta: las filas de esta pantalla traen su
        // fondo de un estilo compartido, sin estado enfocado. Va después de
        // construir los controles, porque el recorrido descarta lo que no tenga
        // listener asignado.
        RemoteControl.applyFocusToTree(binding.root, RemoteControl.isEnabled(this))
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    ).toInt()

    private fun refreshAll() {
        val palette = Appearance.palette(this)
        binding.tvPaletteName.text = palette.name
        binding.tvSwatch.background = Appearance.gradient(this, 12f)
        binding.tvApplied.background = Appearance.gradient(this, 18f)
        refreshSwatchSelection()
        refreshSubtitle()
        refreshMovieClick()
        refreshGrids()
    }

    // ---------------- Tema de color ----------------

    private fun buildColorSwatches() {
        binding.colorContainer.removeAllViews()
        Appearance.palettes.forEachIndexed { index, palette ->
            val dot = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(52f), dp(52f)).also {
                    it.marginEnd = dp(10f)
                }
                gravity = Gravity.CENTER
                textSize = 18f
                setTextColor(ContextCompat.getColor(this@PersonalizeActivity, R.color.text_light))
                isClickable = true
                isFocusable = true
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(palette.start, palette.end)
                ).apply { shape = GradientDrawable.OVAL }
                setOnClickListener {
                    Appearance.setPaletteIndex(this@PersonalizeActivity, index)
                    refreshAll()
                }
            }
            binding.colorContainer.addView(dot)
        }
    }

    private fun refreshSwatchSelection() {
        val selected = Appearance.getPaletteIndex(this)
        for (i in 0 until binding.colorContainer.childCount) {
            (binding.colorContainer.getChildAt(i) as? TextView)?.text = if (i == selected) "✓" else ""
        }
    }

    // ---------------- Tamaño de subtítulos ----------------

    private fun buildSubtitleControls() {
        binding.btnSubtitleMinus.setOnClickListener {
            Appearance.setSubtitleSize(this, Appearance.getSubtitleSize(this) - 2)
            refreshSubtitle()
        }
        binding.btnSubtitlePlus.setOnClickListener {
            Appearance.setSubtitleSize(this, Appearance.getSubtitleSize(this) + 2)
            refreshSubtitle()
        }
    }

    private fun refreshSubtitle() {
        val size = Appearance.getSubtitleSize(this)
        binding.tvSubtitleValue.text = getString(R.string.subtitle_value, size)
        binding.tvSubtitleCurrent.text = getString(R.string.subtitle_current, size)
        binding.tvSubtitlePreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
    }

    // ---------------- Al tocar una película ----------------

    private fun buildMovieClickOptions() {
        binding.optPlayDirect.setOnClickListener {
            Appearance.setMovieClick(this, Appearance.CLICK_PLAY)
            refreshMovieClick()
        }
        binding.optShowDetails.setOnClickListener {
            Appearance.setMovieClick(this, Appearance.CLICK_DETAILS)
            refreshMovieClick()
        }
    }

    private fun refreshMovieClick() {
        val mode = Appearance.getMovieClick(this)
        val accent = Appearance.accent(this)
        val muted = ContextCompat.getColor(this, R.color.text_muted)

        val playSelected = mode == Appearance.CLICK_PLAY
        binding.optPlayDirect.background = optionBackground(playSelected)
        binding.optShowDetails.background = optionBackground(!playSelected)

        binding.radioPlay.text = if (playSelected) "◉" else "○"
        binding.radioDetails.text = if (playSelected) "○" else "◉"
        binding.radioPlay.setTextColor(if (playSelected) accent else muted)
        binding.radioDetails.setTextColor(if (playSelected) muted else accent)
    }

    /** Tarjeta con borde de acento cuando está seleccionada. */
    private fun optionBackground(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12f).toFloat()
            setColor(ContextCompat.getColor(this@PersonalizeActivity, R.color.glass_card))
            if (selected) {
                setStroke(dp(2f), Appearance.accent(this@PersonalizeActivity))
            } else {
                setStroke(dp(1f), ContextCompat.getColor(this@PersonalizeActivity, R.color.glass_border))
            }
        }

    // ---------------- Densidad de las grillas ----------------

    private fun buildGridOptions() {
        fillGrid(binding.seriesGridOptions, isSeries = true)
        fillGrid(binding.moviesGridOptions, isSeries = false)
    }

    private fun fillGrid(container: LinearLayout, isSeries: Boolean) {
        container.removeAllViews()
        Appearance.gridOptions(this).forEach { cols ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                    it.marginEnd = dp(8f)
                }
                setPadding(dp(8f), dp(14f), dp(8f), dp(14f))
                isClickable = true
                isFocusable = true
                addView(TextView(context).apply {
                    text = getString(R.string.grid_option, cols)
                    setTextColor(ContextCompat.getColor(context, R.color.text_light))
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = Appearance.columnsLabel(context, cols)
                    setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                    textSize = 11f
                    gravity = Gravity.CENTER
                })
                setOnClickListener {
                    if (isSeries) Appearance.setSeriesColumns(this@PersonalizeActivity, cols)
                    else Appearance.setMoviesColumns(this@PersonalizeActivity, cols)
                    refreshGrids()
                }
            }
            container.addView(card)
        }
    }

    private fun refreshGrids() {
        val seriesCols = Appearance.getSeriesColumns(this)
        val moviesCols = Appearance.getMoviesColumns(this)
        binding.tvSeriesHint.text =
            getString(R.string.grid_hint, Appearance.columnsLabel(this, seriesCols), seriesCols)
        binding.tvMoviesHint.text =
            getString(R.string.grid_hint, Appearance.columnsLabel(this, moviesCols), moviesCols)

        Appearance.gridOptions(this).forEachIndexed { i, cols ->
            binding.seriesGridOptions.getChildAt(i)?.background = optionBackground(cols == seriesCols)
            binding.moviesGridOptions.getChildAt(i)?.background = optionBackground(cols == moviesCols)
        }
    }
}
