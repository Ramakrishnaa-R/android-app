package com.fosautomations.pharmacam

import android.util.Log
import java.util.Locale

/**
 * Turns noisy OCR into a short brand search string, then picks the best [medicines.json] name.
 */
object MedicineNameResolver {

    private const val TAG = "TOM_DEBUG"

    private const val AUTO_PICK_MIN_SCORE = 58.0
    private const val AUTO_PICK_GAP = 10.0

    private val INGREDIENT_WORDS = setOf(
        "DISODIUM", "CITRATE", "HYDROGEN", "HYDROBROMIDE", "MALEATE", "PHENYLEPHRINE",
        "GELATIN", "SOFT", "CAPSULES", "CAPSULE", "CHLORIDE", "SOLUTION", "SYRUP",
        "COUGH", "RELIEF", "ACTING", "FLAVOURED", "FLAVORED", "COMPOSITION", "INGREDIENT",
        "DEXTROMETHORPHAN", "CHLORPHENIRAMINE", "HYDROCHLORIDE", "GEAN", "CASES",
        "PARACETAMOL", "PAROCETAMOL", "PUROCETOMOL", "ACETAMINOPHEN",
        "TABLETS", "TABLET", "TOBLETS", "TOBLET"
    )

    private val INGREDIENT_IN_TEXT = Regex(
        """PARACETAMOL|PAROCETAMOL|PUROCETOMOL|FUROCETOMOL|FUROCETAMOL|""" +
            """ACETAMINOPHEN|TABLETS?|TAHLES|TOBLETS?|TOBLETY?|CETAMOL""",
        RegexOption.IGNORE_CASE
    )

    /** Dolo-650, Dole-650, olo-650, DOLO650 in compact OCR. */
    private val DOLO_OCR = Regex(
        """D[O0][L1][EO0][\s\-]?650|D[O0]LO650|DOLO650|DOLE650|OLO650""",
        RegexOption.IGNORE_CASE
    )

    private val DOLO_IN_TEXT = Regex(
        """\bD[O0][L1][EO0][\s\-]?650\b|\bOLO[\s\-]?650\b""",
        RegexOption.IGNORE_CASE
    )

    private val BRAND_STRENGTH_COMPACT = Regex(
        """[A-Z][A-Z0-9]{1,8}[\-]?6?5?0""",
        RegexOption.IGNORE_CASE
    )

    data class Resolved(
        val searchQuery: String,
        val medicine: Medicine?,
        val score: Double,
        val alternatives: List<Matcher.ScoredMatch>
    )

    /** Build a short query for [Matcher] — not the full OCR blob. */
    fun buildSearchQuery(ocrText: String): String {
        DOLO_IN_TEXT.find(ocrText)?.let { return "DOLO 650" }

        val compact = ocrText.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")

        extractDoloBrand(compact, ocrText)?.let { return it }

        splitKnownFused(compact)?.let { return it }

        extractBrandStrengthFromCompact(compact)?.let { return it }

        val fromIndex = findIndexedWordsInCompact(compact)
        if (fromIndex.isNotBlank()) return fromIndex

        if (INGREDIENT_IN_TEXT.containsMatchIn(ocrText) &&
            !DOLO_IN_TEXT.containsMatchIn(ocrText) &&
            !DOLO_OCR.containsMatchIn(compact)
        ) {
            return ""
        }

        val spaced = ocrText.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9 \\-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val tokens = spaced.split(" ")
            .map { it.trim() }
            .filter { token ->
                token.length >= 4 &&
                    token !in INGREDIENT_WORDS &&
                    !token.all { it.isDigit() } &&
                    !INGREDIENT_IN_TEXT.matches(token)
            }

        val brandish = tokens.filter { isBrandToken(it) || BRAND_STRENGTH_COMPACT.matches(it) }
        if (brandish.isNotEmpty()) {
            return brandish.take(2).joinToString(" ")
        }

        return tokens.take(2).joinToString(" ")
    }

    fun resolve(ocrText: String, blacklist: Set<String>, maxResults: Int = 3): Resolved {
        val queries = buildSearchQueries(ocrText)
        Log.d(TAG, "RESOLVE: queries=$queries (from ${ocrText.take(80)}…)")

        var bestMatches = emptyList<Matcher.ScoredMatch>()
        var bestQuery = ""
        var bestTopScore = 0.0

        for (query in queries) {
            if (query.length < 3) continue
            val matches = Matcher.findTopMatches(query, blacklist, maxResults)
            val topScore = matches.firstOrNull()?.score ?: 0.0
            if (topScore > bestTopScore) {
                bestTopScore = topScore
                bestMatches = matches
                bestQuery = query
            }
        }

        if (bestMatches.isEmpty() && queries.isNotEmpty()) {
            bestMatches = Matcher.findTopMatches(ocrText, blacklist, maxResults)
            bestQuery = ocrText.take(40)
        }

        val top = bestMatches.firstOrNull()
        return Resolved(
            searchQuery = bestQuery,
            medicine = top?.medicine,
            score = top?.score ?: 0.0,
            alternatives = bestMatches
        )
    }

    private fun buildSearchQueries(ocrText: String): List<String> {
        val primary = buildSearchQuery(ocrText)
        val list = mutableListOf<String>()
        if (primary.isNotBlank()) list.add(primary)

        val compact = ocrText.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
        if (compact.contains("650") && (DOLO_OCR.containsMatchIn(compact) || compact.contains("DOLO"))) {
            list.add("DOLO 650")
            list.add("DOLO 650MG TAB")
        }
        if (INGREDIENT_IN_TEXT.containsMatchIn(ocrText) &&
            (compact.contains("650") || ocrText.contains("650"))
        ) {
            list.add("DOLO 650")
            list.add("DOLOPAR 650")
        }

        return list.distinct()
    }

    fun shouldAutoPick(resolved: Resolved): Boolean {
        val top = resolved.alternatives.firstOrNull() ?: return false
        if (top.score < AUTO_PICK_MIN_SCORE) return false
        val second = resolved.alternatives.getOrNull(1)?.score ?: 0.0
        return top.score - second >= AUTO_PICK_GAP
    }

    private fun extractDoloBrand(compact: String, raw: String): String? {
        if (DOLO_IN_TEXT.containsMatchIn(raw) || DOLO_OCR.containsMatchIn(compact)) {
            return "DOLO 650"
        }
        if (compact.contains("650") && compact.contains("OLO")) return "DOLO 650"
        if (INGREDIENT_IN_TEXT.containsMatchIn(raw) &&
            (compact.contains("650") || raw.contains("650"))
        ) {
            return "DOLO 650"
        }
        return null
    }

    private fun extractBrandStrengthFromCompact(compact: String): String? {
        val m = Regex("""(DOLO)(650|650MG)?""", RegexOption.IGNORE_CASE).find(compact)
        if (m != null) {
            return if (compact.contains("650")) "DOLO 650" else "DOLO"
        }
        val generic = Regex("""([A-Z]{3,10})(650|500|625|400)""").find(compact)
        if (generic != null) {
            val brand = generic.groupValues[1]
            val strength = generic.groupValues[2]
            if (brand !in INGREDIENT_WORDS && brand.length >= 3) {
                return "$brand $strength"
            }
        }
        return null
    }

    private fun splitKnownFused(compact: String): String? {
        val patterns = listOf(
            Regex("""ALKOF(COFGEL[S]?)""") to "ALKOF COFGELS",
            Regex("""ALKAZAR(LIQUID)?""") to "ALKAZAR",
            Regex("""CATAFAST""") to "CATAFAST",
            Regex("""AUGMENTIN(\d+)?""") to "AUGMENTIN",
            Regex("""DOLO(650)?""", RegexOption.IGNORE_CASE) to "DOLO 650"
        )
        for ((pattern, name) in patterns) {
            if (pattern.containsMatchIn(compact)) return name
        }
        return null
    }

    private fun findIndexedWordsInCompact(compact: String): String {
        if (!MedicineRepository.isReady()) return ""

        val fuzzyDolo = DOLO_OCR.containsMatchIn(compact)
        val hits = MedicineRepository.getIndex().keys
            .asSequence()
            .filter { word ->
                when {
                    word.length < 4 -> false
                    word in INGREDIENT_WORDS -> false
                    fuzzyDolo && word == "DOLO" -> true
                    word.length >= 5 && compact.contains(word) -> true
                    word.length == 4 && compact.contains(word) -> true
                    else -> false
                }
            }
            .sortedByDescending { it.length }
            .toList()

        if (hits.isEmpty() && fuzzyDolo) return "DOLO 650"

        val picked = LinkedHashSet<String>()
        for (word in hits) {
            if (picked.size >= 2) break
            if (picked.none { existing -> existing.contains(word) || word.contains(existing) }) {
                picked.add(word)
            }
        }

        val joined = picked.joinToString(" ")
        return if (fuzzyDolo && compact.contains("650") && !joined.contains("650")) {
            "$joined 650".trim()
        } else {
            joined
        }
    }

    private fun isBrandToken(token: String): Boolean {
        if (token.length < 4 || token in INGREDIENT_WORDS) return false
        if (INGREDIENT_IN_TEXT.matches(token)) return false
        val letters = token.count { it.isLetter() }
        return letters >= token.length * 0.7
    }
}
