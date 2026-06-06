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

    fun isReady(): Boolean = synchronized(database) { isLoaded && database.isNotEmpty() }

    fun getIndex(): Map<String, Set<Medicine>> = synchronized(invertedIndex) { invertedIndex.toMap() }

    fun getByCategory(category: ProductCategory): List<Medicine> = synchronized(categoryIndex) { 
        categoryIndex[category]?.toList().orEmpty() 
    }

    /** Medicines for matcher when a package category is predicted. */
    fun getByCategoryFilter(filter: ProductCategory): List<Medicine> = synchronized(database) {
        val categories = ProductCategoryClassifier.matchingCategories(filter)
        if (categories.size >= ProductCategory.entries.size) return database.toList()
        val seen = LinkedHashSet<Medicine>()
        categories.forEach { category ->
            categoryIndex[category]?.let { seen.addAll(it) }
        }
        seen.toList()
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
                Log.d("PharmaCam", "Database loaded: ${database.size} items")
            } catch (e: Exception) {
                Log.e("PharmaCam", "Error loading database", e)
            }
        }
    }

    fun loadForTest(medicines: List<String>) {
        synchronized(database) {
            database.clear()
            invertedIndex.clear()
            categoryIndex.clear()
            medicines.forEach { name ->
                val med = Medicine(name, UUID.randomUUID().toString())
                addInternalLocked(med)
            }
            isLoaded = true
        }
    }

    private fun parseJsonArray(jsonArray: JSONArray) {
        synchronized(database) {
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
                medicine?.let { addInternalLocked(it) }
            }
        }
    }

    private fun addInternalLocked(medicine: Medicine) {
        database.add(medicine)
        val category = ProductCategoryClassifier.classify(medicine.name, ClassificationSource.DATABASE)
        categoryIndex.getOrPut(category) { mutableListOf() }.add(medicine)
        tokenize(medicine.name).forEach { word ->
            if (word.length >= 3 && (word !in NOISE)) {
                invertedIndex.getOrPut(word) { mutableSetOf() }.add(medicine)
            }
        }
    }

    private fun fuseShortTokens(tokens: List<String>): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.length < 3 && i + 1 < tokens.size) {
                val next = tokens[i + 1]
                if (!next.all { it.isDigit() } && !token.all { it.isDigit() }) {
                    result.add(token + next)
                    if (next.length < 3 && i + 2 < tokens.size) {
                        val third = tokens[i + 2]
                        if (!third.all { it.isDigit() }) {
                            result.add(token + next + third)
                        }
                    }
                }
            }
            result.add(token)
            i++
        }
        return result
    }

    fun tokenize(text: String): List<String> {
        val rawUpper = text.uppercase(Locale.ROOT)
        val spaceSplit = rawUpper.replace("[^A-Z0-9 \\-]".toRegex(), "")
            .split("\\s+".toRegex())
            .filter { it.isNotEmpty() }
        
        val splitHyphens = spaceSplit.flatMap { token ->
            if (token.contains("-")) {
                val parts    = token.split("-").filter { it.isNotEmpty() }
                val noHyphen = token.replace("-", "")
                if (noHyphen.isNotEmpty()) parts + noHyphen else parts
            } else {
                listOf(token)
            }
        }
        
        val fused = fuseShortTokens(splitHyphens)
        return fused.map { it.replace("[^A-Z0-9]".toRegex(), "") }.filter { it.isNotEmpty() }
    }

    suspend fun addMedicine(context: Context, medicine: Medicine) {
        withContext(Dispatchers.IO) {
            synchronized(database) {
                addInternalLocked(medicine)
            }
            save(context)
        }
    }

    suspend fun deleteMedicine(context: Context, id: String) {
        withContext(Dispatchers.IO) {
            synchronized(database) {
                val med = database.find { it.id == id }
                if (med != null) {
                    database.remove(med)
                    rebuildIndexLocked()
                }
            }
            save(context)
        }
    }

    suspend fun updateMedicine(context: Context, id: String, newName: String) {
        withContext(Dispatchers.IO) {
            synchronized(database) {
                val index = database.indexOfFirst { it.id == id }
                if (index != -1) {
                    database[index] = database[index].copy(name = newName)
                    rebuildIndexLocked()
                }
            }
            save(context)
        }
    }

    private fun rebuildIndexLocked() {
        invertedIndex.clear()
        categoryIndex.clear()
        database.forEach { med ->
            val category = ProductCategoryClassifier.classify(med.name, ClassificationSource.DATABASE)
            categoryIndex.getOrPut(category) { mutableListOf() }.add(med)
            tokenize(med.name).forEach { word ->
                if (word.length >= 3 && (word !in NOISE)) {
                    invertedIndex.getOrPut(word) { mutableSetOf() }.add(med)
                }
            }
        }
    }

    private fun save(context: Context) {
        val jsonArray = JSONArray()
        synchronized(database) {
            database.forEach { med ->
                val obj = JSONObject()
                obj.put("name", med.name)
                obj.put("id", med.id)
                jsonArray.put(obj)
            }
        }
        File(context.filesDir, "user_medicines.json").writeText(jsonArray.toString())
    }
}
