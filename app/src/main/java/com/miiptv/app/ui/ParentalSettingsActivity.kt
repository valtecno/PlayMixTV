package com.miiptv.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.miiptv.app.R
import com.miiptv.app.api.Category
import com.miiptv.app.api.Session
import com.miiptv.app.databinding.ActivityParentalSettingsBinding
import com.miiptv.app.util.DeviceMode
import com.miiptv.app.util.Parental
import com.miiptv.app.util.PinDialog
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ParentalSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentalSettingsBinding
    private lateinit var adapter: CategoryLockAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceMode.lockPortraitIfMobile(this)
        binding = ActivityParentalSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        updatePinButton()
        binding.btnPin.setOnClickListener {
            if (Parental.hasPin(this)) {
                PinDialog.create(this) { updatePinButton() } // cambiar PIN
            } else {
                PinDialog.create(this) { updatePinButton() } // crear PIN
            }
        }
        binding.btnRemovePin.setOnClickListener {
            if (Parental.hasPin(this)) {
                PinDialog.ask(this) {
                    Parental.removePin(this)
                    Toast.makeText(this, "PIN eliminado", Toast.LENGTH_SHORT).show()
                    updatePinButton()
                }
            }
        }

        adapter = CategoryLockAdapter(this)
        binding.recyclerCategories.layoutManager = LinearLayoutManager(this)
        binding.recyclerCategories.adapter = adapter
        loadAllCategories()
    }

    private fun updatePinButton() {
        binding.btnPin.text = if (Parental.hasPin(this)) getString(R.string.parental_change_pin) else getString(R.string.parental_create_pin)
    }

    private fun loadAllCategories() {
        // Combina categorías de Live + Movies + Series para poder bloquearlas todas desde un solo lugar
        val all = mutableListOf<Category>()
        var pending = 3
        val onDone = {
            pending--
            if (pending == 0) adapter.submitList(all.distinctBy { it.categoryId })
        }
        Session.api(this).getLiveCategories(Session.username(this), Session.password(this)).enqueue(catCallback(all, onDone))
        Session.api(this).getVodCategories(Session.username(this), Session.password(this)).enqueue(catCallback(all, onDone))
        Session.api(this).getSeriesCategories(Session.username(this), Session.password(this)).enqueue(catCallback(all, onDone))
    }

    private fun catCallback(sink: MutableList<Category>, onDone: () -> Unit) = object : Callback<List<Category>> {
        override fun onResponse(call: Call<List<Category>>, response: Response<List<Category>>) {
            sink.addAll(response.body().orEmpty())
            onDone()
        }
        override fun onFailure(call: Call<List<Category>>, t: Throwable) { onDone() }
    }
}

class CategoryLockAdapter(private val context: android.content.Context) : RecyclerView.Adapter<CategoryLockAdapter.VH>() {
    private val items = mutableListOf<Category>()

    fun submitList(newItems: List<Category>) {
        items.clear(); items.addAll(newItems); notifyDataSetChanged()
    }

    inner class VH(val root: ViewGroup, val name: TextView, val switch: android.widget.Switch) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category_lock, parent, false) as ViewGroup
        val name = view.findViewById<TextView>(R.id.tvCategoryName)
        val switch = view.findViewById<android.widget.Switch>(R.id.switchLock)
        return VH(view, name, switch)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cat = items[position]
        holder.name.text = cat.categoryName
        holder.switch.setOnCheckedChangeListener(null)
        holder.switch.isChecked = Parental.isCategoryLocked(context, cat.categoryId)
        holder.switch.setOnCheckedChangeListener { _, checked ->
            Parental.setCategoryLocked(context, cat.categoryId, checked)
        }
    }

    override fun getItemCount() = items.size
}
