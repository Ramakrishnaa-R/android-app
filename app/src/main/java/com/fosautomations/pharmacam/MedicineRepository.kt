package com.fosautomations.pharmacam

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

object MedicineRepository {
    private val database = mutableListOf<Medicine>()
    private val invertedIndex = mutableMapOf<String, MutableSet<Medicine>>()
    private var isLoaded = false

    private val NOISE = setOf(
        "IP", "W", "WV", "WITH", "AND", "FOR", "USE", "ONLY", "EXP", "MFG", "BATCH", "NO",
        "DATE", "MRP", "EXTERNAL", "TREATMENT", "INFECTION", "THE"
    )

    fun getDatabase(): List<Medicine> = database

    fun getIndex(): Map<String, Set<Medicine>> = invertedIndex

    suspend fun loadIfNeeded(context: Context) {
        if (isLoaded) return
        withContext(Dispatchers.IO) {
            try {
                val localFile = File(context.filesDir, "user_medicines.json")
                val jsonString = if (localFile.exists()) {
                    localFile.readText()
                } else {
                    context.assets.open("medicines.json").bufferedReader().use { it.readText() }
                }
                
                val jsonArray = JSONArray(jsonString)
                parseJsonArray(jsonArray)
                isLoaded = true
                Log.d("PharmaCam", "Database loaded: ${database.size} items")
            } catch (e: Exception) {
                Log.e("PharmaCam", "Error loading database", e)
            }
        }
    }

    private fun parseJsonArray(jsonArray: JSONArray) {
        database.clear()
        invertedIndex.clear()
        
        for (i in 0 until jsonArray.length()) {
            val medicine = when (val item = jsonArray.get(i)) {
                is JSONObject -> Medicine(
                    item.optString("name", "Unknown"),
                    item.optString("id", UUID.randomUUID().toString())
                )
                is String -> Medicine(item, UUID.randomUUID().toString())
                else -> null
            }
            medicine?.let { addInternal(it) }
        }
    }

    private fun addInternal(medicine: Medicine) {
        database.add(medicine)
        tokenize(medicine.name).forEach { word ->
            if (word.length >= 3 && (word !in NOISE)) {
                invertedIndex.getOrPut(word) { mutableSetOf() }.add(medicine)
            }
        }
    }

    fun tokenize(text: String): List<String> {
        return text.uppercase(Locale.ROOT)
            .replace("-", " ")
            .replace("[^A-Z0-9 ]".toRegex(), "")
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
    }

    suspend fun addMedicine(context: Context, medicine: Medicine) {
        withContext(Dispatchers.IO) {
            addInternal(medicine)
            save(context)
        }
    }

    suspend fun deleteMedicine(context: Context, id: String) {
        withContext(Dispatchers.IO) {
            val med = database.find { it.id == id }
            if (med != null) {
                database.remove(med)
                rebuildIndex()
                save(context)
            }
        }
    }

    suspend fun updateMedicine(context: Context, id: String, newName: String) {
        withContext(Dispatchers.IO) {
            val index = database.indexOfFirst { it.id == id }
            if (index != -1) {
                database[index] = database[index].copy(name = newName)
                rebuildIndex()
                save(context)
            }
        }
    }

    private fun rebuildIndex() {
        invertedIndex.clear()
        database.forEach { med ->
            tokenize(med.name).forEach { word ->
                if (word.length >= 3 && (word !in NOISE)) {
                    invertedIndex.getOrPut(word) { mutableSetOf() }.add(med)
                }
            }
        }
    }

    private fun save(context: Context) {
        val jsonArray = JSONArray()
        database.forEach { med ->
            val obj = JSONObject()
            obj.put("name", med.name)
            obj.put("id", med.id)
            jsonArray.put(obj)
        }
        File(context.filesDir, "user_medicines.json").writeText(jsonArray.toString())
    }
}
