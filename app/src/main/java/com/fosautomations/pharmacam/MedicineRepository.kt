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
    private val categoryIndex = mutableMapOf<ProductCategory, MutableList<Medicine>>()
    private var isLoaded = false

    private val NOISE = setOf(
        "IP", "W", "WV", "WITH", "AND", "FOR", "USE", "ONLY", "EXP", "MFG", "BATCH", "NO",
        "DATE", "MRP", "EXTERNAL", "TREATMENT", "INFECTION", "THE"
    )

    fun getDatabase(): List<Medicine> = synchronized(database) { database.toList() }

    fun getIndex(): Map<String, Set<Medicine>> = synchronized(invertedIndex) { invertedIndex.toMap() }

    fun getByCategory(category: ProductCategory): List<Medicine> = synchronized(categoryIndex) { categoryIndex[category]?.toList().orEmpty() }

    /** Medicines for matcher when a package category is predicted (includes related forms, e.g. Tonic + Suspension). */
    fun getByCategoryFilter(filter: ProductCategory): List<Medicine> = synchronized(database) {
        val categories = ProductCategoryClassifier.matchingCategories(filter)
        if (categories.size >= ProductCategory.entries.size) return database.toList()
        val seen = LinkedHashSet<Medicine>()
        categories.forEach { category ->
            categoryIndex[category]?.let { seen.addAll(it) }
        }
        seen.toList()
    }

    fun getCategoryCounts(): Map<ProductCategory, Int> = synchronized(categoryIndex) {
        ProductCategory.entries.associateWith { categoryIndex[it]?.size ?: 0 }
    }

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
                logCategoryIndexStats()
                Log.d("PharmaCam", "Database loaded: ${database.size} items")
            } catch (e: Exception) {
                Log.e("PharmaCam", "Error loading database", e)
            }
        }
    }

    private fun parseJsonArray(jsonArray: JSONArray) {
        database.clear()
        invertedIndex.clear()
        categoryIndex.clear()
        
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

    private fun addInternal(medicine: Medicine) = synchronized(database) {
        database.add(medicine)
        val category = ProductCategoryClassifier.classify(
            medicine.name,
            ClassificationSource.DATABASE
        )
        categoryIndex.getOrPut(category) { mutableListOf() }.add(medicine)
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

    private fun rebuildIndex() = synchronized(database) {
        invertedIndex.clear()
        categoryIndex.clear()
        database.forEach { med ->
            categoryIndex.getOrPut(
                ProductCategoryClassifier.classify(med.name, ClassificationSource.DATABASE)
            ) { mutableListOf() }.add(med)
            tokenize(med.name).forEach { word ->
                if (word.length >= 3 && (word !in NOISE)) {
                    invertedIndex.getOrPut(word) { mutableSetOf() }.add(med)
                }
            }
        }
    }

    private fun logCategoryIndexStats() {
        val counts = getCategoryCounts()
        val pill = counts[ProductCategory.PILL] ?: 0
        val other = counts[ProductCategory.OTHER] ?: 0
        val total = database.size.coerceAtLeast(1)
        val summary = ProductCategory.entries
            .sortedByDescending { counts[it] ?: 0 }
            .joinToString { "${it.name}=${counts[it] ?: 0}" }
        Log.i(
            "PharmaCam",
            "Category index built: PILL=$pill (${pill * 100 / total}%), OTHER=$other (${other * 100 / total}%), all=[$summary]"
        )
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
