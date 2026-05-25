package com.fosautomations.pharmacam

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class MedicineDbActivity : AppCompatActivity() {

    private lateinit var medInput: EditText
    private lateinit var searchBar: EditText
    private lateinit var addMedBtn: Button
    private lateinit var medRecyclerView: RecyclerView
    private lateinit var loader: ProgressBar
    private lateinit var adapter: MedicineAdapter
    
    private var filteredMedicines = mutableListOf<Medicine>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_medicine_db)

        medInput = findViewById(R.id.medInput)
        searchBar = findViewById(R.id.searchBar)
        addMedBtn = findViewById(R.id.addMedBtn)
        medRecyclerView = findViewById(R.id.medRecyclerView)
        loader = findViewById(R.id.dbLoader)

        setupRecyclerView()
        loadData()

        addMedBtn.setOnClickListener {
            val name = medInput.text.toString().trim()
            if (name.isNotEmpty()) {
                val existing = MedicineRepository.getDatabase().any { it.name.equals(name, true) }
                if (!existing) {
                    val newMed = Medicine(name, UUID.randomUUID().toString())
                    lifecycleScope.launch {
                        MedicineRepository.addMedicine(this@MedicineDbActivity, newMed)
                        filter(searchBar.text.toString())
                        medInput.text.clear()
                    }
                } else {
                    Toast.makeText(this, "Already exists", Toast.LENGTH_SHORT).show()
                }
            }
        }

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                lifecycleScope.launch {
                    filter(s.toString())
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupRecyclerView() {
        adapter = MedicineAdapter(filteredMedicines) { medicine ->
            showOptionsDialog(medicine)
        }
        medRecyclerView.layoutManager = LinearLayoutManager(this)
        medRecyclerView.adapter = adapter
    }

    private fun loadData() {
        lifecycleScope.launch {
            loader.visibility = View.VISIBLE
            MedicineRepository.loadIfNeeded(this@MedicineDbActivity)
            filter("")
            loader.visibility = View.GONE
        }
    }

    private suspend fun filter(query: String) {
        val all = MedicineRepository.getDatabase()
        val result = withContext(Dispatchers.Default) {
            if (query.isEmpty()) {
                all.sortedBy { it.name }.toMutableList()
            } else {
                val lowerQuery = query.lowercase(Locale.ROOT)
                all.filter { it.name.lowercase(Locale.ROOT).contains(lowerQuery) }
                    .sortedBy { it.name }
                    .toMutableList()
            }
        }
        filteredMedicines.clear()
        filteredMedicines.addAll(result)
        adapter.notifyDataSetChanged()
    }

    private fun showOptionsDialog(medicine: Medicine) {
        val options = arrayOf("Edit", "Delete")
        AlertDialog.Builder(this)
            .setTitle(medicine.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditDialog(medicine)
                    1 -> showDeleteConfirmation(medicine)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditDialog(medicine: Medicine) {
        val input = EditText(this)
        input.setText(medicine.name)
        input.setSelection(medicine.name.length)
        
        AlertDialog.Builder(this)
            .setTitle("Edit Medicine")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != medicine.name) {
                    lifecycleScope.launch {
                        MedicineRepository.updateMedicine(this@MedicineDbActivity, medicine.id, newName)
                        filter(searchBar.text.toString())
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmation(medicine: Medicine) {
        AlertDialog.Builder(this)
            .setTitle("Delete Medicine")
            .setMessage("Are you sure you want to delete '${medicine.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    MedicineRepository.deleteMedicine(this@MedicineDbActivity, medicine.id)
                    filter(searchBar.text.toString())
                    Toast.makeText(this@MedicineDbActivity, "Removed: ${medicine.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class MedicineAdapter(
        private val list: List<Medicine>,
        private val onItemClick: (Medicine) -> Unit
    ) : RecyclerView.Adapter<MedicineAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvId: TextView = view.findViewById(R.id.tvId)
            val tvName: TextView = view.findViewById(R.id.tvName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_medicine, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val med = list[position]
            holder.tvId.text = (position + 1).toString()
            holder.tvName.text = med.name.uppercase()
            holder.itemView.setOnClickListener { onItemClick(med) }
        }

        override fun getItemCount(): Int = list.size
    }
}
