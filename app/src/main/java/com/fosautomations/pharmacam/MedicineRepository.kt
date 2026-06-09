package com.fosautomations.pharmacam

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

object MedicineRepository {
    private val database = mutableListOf<Medicine>()
    private val nameToMedicineMap = HashMap<String, Medicine>()
    private val nameToVectorMap = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()
    private val invertedIndex = mutableMapOf<String, MutableSet<Medicine>>()
    private val categoryIndex = mutableMapOf<ProductCategory, MutableList<Medicine>>()
    private var isLoaded = false
    var dbIndexingProgress = -1
        private set

    private val NOISE = setOf(
        "IP", "W", "WV", "WITH", "AND", "FOR", "USE", "ONLY", "EXP", "MFG", "BATCH", "NO",
        "DATE", "MRP", "EXTERNAL", "TREATMENT", "INFECTION", "THE"
    )

    private val BOUNDARY_LETTER_DIGIT = Regex("""([A-Z])(\d)""")
    private val BOUNDARY_DIGIT_LETTER = Regex("""(\d)([A-Z])""")
    private val NON_ALPHANUMERIC_SPACED = Regex("[^A-Z0-9 \\-]")
    private val SPACES = Regex("\\s+")
    private val NON_ALPHANUMERIC = Regex("[^A-Z0-9]")

    fun dbNormalize(text: String): String {
        val upper = text.uppercase(Locale.ROOT)
        return upper
            .replace(BOUNDARY_LETTER_DIGIT, "$1 $2")
            .replace(BOUNDARY_DIGIT_LETTER, "$1 $2")
            .replace(NON_ALPHANUMERIC_SPACED, " ")
            .replace(SPACES, " ")
            .trim()
    }

    fun getDatabase(): List<Medicine> = synchronized(database) { database.toList() }

    fun getMedicineByName(name: String): Medicine? = synchronized(database) {
        nameToMedicineMap[dbNormalize(name)]
    }

    fun getEmbedding(name: String): FloatArray? = getEmbeddingDirect(dbNormalize(name))

    fun getEmbeddingDirect(normalizedName: String): FloatArray? = nameToVectorMap[normalizedName]

    fun isReady(): Boolean = synchronized(database) { isLoaded && database.isNotEmpty() }

    fun isEmbeddingsReady(): Boolean {
        return isLoaded && dbIndexingProgress < 0 && nameToVectorMap.isNotEmpty()
    }

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

                // Load or precompute semantic search embeddings asynchronously in the background
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    loadEmbeddingsIfNeeded(context)
                }
            } catch (e: Exception) {
                Log.e("PharmaCam", "Error loading database", e)
            }
        }
    }

    private fun loadEmbeddingsIfNeeded(context: Context) {
        val binFile = File(context.filesDir, "medicines_embeddings.bin")
        if (binFile.exists()) {
            try {
                binFile.inputStream().use { fis ->
                    val dis = java.io.DataInputStream(fis)
                    val size = dis.readInt()
                    val dim = dis.readInt()
                    for (i in 0 until size) {
                        val name = dis.readUTF()
                        val embeddingSize = dis.readInt()
                        val array = FloatArray(embeddingSize)
                        for (j in 0 until embeddingSize) {
                            array[j] = dis.readFloat()
                        }
                        nameToVectorMap[dbNormalize(name)] = array
                    }
                }
                Log.d("PharmaCam", "Embeddings loaded from binary cache: ${nameToVectorMap.size} items")
                return
            } catch (e: Exception) {
                Log.e("PharmaCam", "Failed to read binary embeddings cache, recomputing...", e)
                nameToVectorMap.clear()
            }
        }

        Log.d("PharmaCam", "Precomputing embeddings for database...")
        dbIndexingProgress = 0
        try {
            TextEmbeddingHelper(context).use { helper ->
                database.forEachIndexed { index, med ->
                    val norm = dbNormalize(med.name)
                    val vector = helper.getEmbedding(norm)
                    if (vector != null) {
                        nameToVectorMap[norm] = vector
                    }
                    if (index % 150 == 0 || index == database.lastIndex) {
                        dbIndexingProgress = (index * 100 / database.size).coerceIn(0, 100)
                    }
                }

                // Write binary cache
                binFile.outputStream().use { fos ->
                    val dos = java.io.DataOutputStream(fos)
                    dos.writeInt(nameToVectorMap.size)
                    val sampleVector = nameToVectorMap.values.firstOrNull()
                    val dim = sampleVector?.size ?: 256
                    dos.writeInt(dim)
                    nameToVectorMap.forEach { (name, vector) ->
                        dos.writeUTF(name)
                        dos.writeInt(vector.size)
                        vector.forEach { f -> dos.writeFloat(f) }
                    }
                    dos.flush()
                }
                Log.d("PharmaCam", "Embeddings precomputed and cached: ${nameToVectorMap.size} items")
            }
        } catch (e: Exception) {
            Log.e("PharmaCam", "Error precomputing embeddings: ${e.message}", e)
        } finally {
            dbIndexingProgress = -1
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
        nameToMedicineMap[dbNormalize(medicine.name)] = medicine
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
        val spaceSplit = rawUpper.replace(NON_ALPHANUMERIC_SPACED, "")
            .split(SPACES)
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
        return fused.map { it.replace(NON_ALPHANUMERIC, "") }.filter { it.isNotEmpty() }
    }

    suspend fun addMedicine(context: Context, medicine: Medicine) {
        withContext(Dispatchers.IO) {
            synchronized(database) {
                addInternalLocked(medicine)
            }
            save(context)
            loadEmbeddingsIfNeeded(context)
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
            loadEmbeddingsIfNeeded(context)
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
            loadEmbeddingsIfNeeded(context)
        }
    }

    private fun rebuildIndexLocked() {
        invertedIndex.clear()
        categoryIndex.clear()
        nameToMedicineMap.clear()
        nameToVectorMap.clear()
        database.forEach { med ->
            nameToMedicineMap[dbNormalize(med.name)] = med
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
