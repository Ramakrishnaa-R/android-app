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
        "TABLETS", "TABLET", "TOBLETS", "TOBLET",
        "STONE", "CUTTER", "MALTA", "MALTO"
    )

    private const val MIN_ACCEPT_SCORE = 55.0

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
    private val AUGMENTIN_OCR_STRENGTH = Regex(
        """\bA?UG?MENTIN\s+([56S2Z]2[5S])\b""",
        RegexOption.IGNORE_CASE
    )

    data class Resolved(
        val searchQuery: String,
        val medicine: Medicine?,
        val score: Double,
        val alternatives: List<Matcher.ScoredMatch>
    )

    /** Merge square + wide OCR so subtitle text (e.g. ALKALIZER) is used for matching. */
    fun combineOcrSources(vararg parts: String): String =
        parts.map { it.trim() }.filter { it.length >= 2 }.joinToString(" ")

    /** Build a short query for [Matcher] — not the full OCR blob. */
    fun buildSearchQuery(ocrText: String): String {
        val upper = ocrText.uppercase(Locale.ROOT)
        if (upper.contains("ALKALIZER") || upper.contains("ALKAFLOW")) return "ALKALIZER"

        DOLO_IN_TEXT.find(ocrText)?.let { return "DOLO 650" }

        normalizeKnownStrengthOcr(upper)?.let { return it }

        val compact = upper.replace(Regex("[^A-Z0-9]"), "")

        detectAlkalizerBrand(compact)?.let { return it }

        extractDoloBrand(compact, ocrText)?.let { return it }

        extractBrandStrengthFromCompact(compact)?.let { return it }

        splitKnownFused(compact)?.let { return it }

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
            return normalizeKnownStrengthOcr(brandish.take(2).joinToString(" "))
                ?: brandish.take(2).joinToString(" ")
        }

        val fallback = tokens.take(2).joinToString(" ")
        return normalizeKnownStrengthOcr(fallback) ?: fallback
    }

    fun resolve(ocrText: String, blacklist: Set<String>, maxResults: Int = 3): Resolved =
        resolveForScan(ocrText, hintOcr = null, blacklist, maxResults)

    /**
     * Accurate scan path: [primaryOcr] = 1:1 square (or pickBest) — used for UI.
     * [hintOcr] = wide crop — only used to extract extra search queries, never merged into raw match text.
     */
    fun resolveForScan(
        primaryOcr: String,
        hintOcr: String?,
        blacklist: Set<String>,
        maxResults: Int = 3
    ): Resolved {
        val queries = linkedSetOf<String>()
        buildSearchQueries(primaryOcr).forEach { queries.add(it) }
        hintOcr?.trim()?.takeIf { it.length >= 3 }?.let { wide ->
            buildSearchQueries(wide).forEach { queries.add(it) }
            buildSearchQuery(wide).takeIf { it.isNotBlank() }?.let { queries.add(it) }
        }
        return resolveWithQueries(
            ocrText = primaryOcr,
            queries = queries.toList(),
            blacklist = blacklist,
            maxResults = maxResults,
            allowRawFallback = false
        )
    }

    fun resolveCombined(
        blacklist: Set<String>,
        maxResults: Int = 5,
        vararg ocrParts: String
    ): Resolved {
        val combined = combineOcrSources(*ocrParts)
        val queries = buildSearchQueries(combined).toMutableList()
        ocrParts.map { it.trim() }.filter { it.length >= 3 }.forEach { part ->
            val q = buildSearchQuery(part)
            if (q.isNotBlank()) queries.add(q)
        }
        return resolveWithQueries(combined, queries.distinct(), blacklist, maxResults, allowRawFallback = false)
    }

    private fun resolveWithQueries(
        ocrText: String,
        queries: List<String>,
        blacklist: Set<String>,
        maxResults: Int,
        allowRawFallback: Boolean = true
    ): Resolved {
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

        if (bestMatches.isEmpty() && allowRawFallback && queries.isNotEmpty()) {
            bestMatches = Matcher.findTopMatches(ocrText, blacklist, maxResults)
            bestQuery = ocrText.take(40)
        }

        bestMatches = filterPlausibleMatches(bestQuery, bestMatches)

        val top = bestMatches.firstOrNull()
        return Resolved(
            searchQuery = bestQuery,
            medicine = top?.medicine,
            score = top?.score ?: 0.0,
            alternatives = bestMatches
        )
    }

    /** Drop weak scores and accessory false positives (e.g. TABLET CUTTER on Alkaflow). */
    private fun filterPlausibleMatches(
        query: String,
        matches: List<Matcher.ScoredMatch>
    ): List<Matcher.ScoredMatch> {
        var filtered = matches.filter { it.score >= MIN_ACCEPT_SCORE }
        val q = query.uppercase(Locale.ROOT)
        if (q.contains("ALKALIZER") || q.contains("ALKAFLOW")) {
            filtered = filtered.filter { it.medicine.name.uppercase().contains("ALKAL") }
        }
        if (q.contains("DOLO")) {
            filtered = filtered.filter {
                val n = it.medicine.name.uppercase()
                n.contains("DOLO") || n.contains("PARACET") || n.contains("CROCIN")
            }
        }
        if (q.contains("AUGMENTIN") && (q.contains("625") || q.contains("525"))) {
            filtered = filtered.filter {
                val n = it.medicine.name.uppercase()
                n.contains("AUGMENTIN") && n.contains("625")
            }
        }
        filtered = filtered.filter { med ->
            val name = med.medicine.name.uppercase()
            !name.contains("TABLET CUTTER") && !name.contains("STONE CUTTER")
        }
        return filtered
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
        if (detectAlkalizerBrand(compact) != null) {
            list.add("ALKALIZER")
            list.add("ALKALIZER 100ML SYP")
        }
        if ((compact.contains("AUGMENTIN") || compact.contains("AGMENTIN")) &&
            (compact.contains("625") || compact.contains("525") || compact.contains("S25") || compact.contains("SZS"))
        ) {
            list.add("AUGMENTIN 625")
            list.add("AUGMENTIN 625 TAB")
        }

        return list.distinct()
    }

    /** Pack brand "Alkaflow" / subtitle ALKALIZER → DB name ALKALIZER 100ML SYP. */
    private fun detectAlkalizerBrand(compact: String): String? {
        if (compact.contains("ALKALIZER") || compact.contains("ALKAFLOW")) return "ALKALIZER"
        if (compact.contains("ALK") && (compact.contains("FLOW") || compact.contains("ALIZER"))) {
            return "ALKALIZER"
        }
        return null
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
        Regex("""A?UG?MENTIN(\d{2,4})""").find(compact)?.let { m ->
            val strength = if (m.groupValues[1] == "525") "625" else m.groupValues[1]
            return "AUGMENTIN $strength"
        }
        Regex("""A?UG?MENTIN[S5Z2]2[5S]""").find(compact)?.let {
            return "AUGMENTIN 625"
        }
        if (compact.contains("AUGMENTIN") || compact.contains("AGMENTIN")) return "AUGMENTIN"

        val patterns = listOf(
            Regex("""ALKOF(COFGEL[S]?)""") to "ALKOF COFGELS",
            Regex("""ALKAZAR(LIQUID)?""") to "ALKAZAR",
            Regex("""CATAFAST""") to "CATAFAST",
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

    private fun normalizeKnownStrengthOcr(text: String): String? {
        val upper = text.uppercase(Locale.ROOT)
        val match = AUGMENTIN_OCR_STRENGTH.find(upper) ?: return null
        val strength = match.groupValues[1]
            .replace('S', '5')
            .replace('Z', '2')
        return if (strength == "625" || strength == "525" || strength == "225") "AUGMENTIN 625" else null
    }
}
