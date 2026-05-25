package com.fosautomations.pharmacam

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity

class MedicineListActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_medicine_list)

        val medicineListView = findViewById<ListView>(R.id.medicineListView)
        val backBtn = findViewById<Button>(R.id.backBtn)

        // Load from SharedPreferences to show the actual saved list
        val prefs = getSharedPreferences("medDB", MODE_PRIVATE)
        val savedSet = prefs.getStringSet("list", setOf(
            "MPTOL 650", "DOLO 650", "RANTAC 150", "CROCIN 650", "CETZINE 10"
        ))
        val medicineList = savedSet?.toList()?.sorted() ?: emptyList()

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            medicineList
        )
        medicineListView.adapter = adapter

        backBtn.setOnClickListener {
            finish()
        }
    }
}
