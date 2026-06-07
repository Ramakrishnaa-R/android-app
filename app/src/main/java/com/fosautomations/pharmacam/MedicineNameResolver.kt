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
        "STONE", "CUTTER", "MALTA", "MALTO",
        // Storage and directions words
        "STORE", "KEEP", "PROTECTED", "DIRECT", "SUNLIGHT", "MOISTURE", "TEMPERATURE",
        "EXCEEDING", "BELOW", "ABOVE", "FREEZE", "WARNING", "CAUTION", "DRUG", "PHYSICIAN",
        "DIRECTED", "DOSAGE", "REACH", "CHILDREN", "OUT", "MARKETED", "MANUFACTURED",
        "BATCH", "EXPIRY", "MRP", "PRICE", "OINTMENT", "CREAM", "GEL", "SUSPENSION",
        "INJECTION", "LIQUID", "DROP", "DROPS", "SPRAY", "INHALER", "POWDER", "DATE",
        "LICENSE", "LICENCE", "REGD", "REGISTERED", "PRESCRIPTION", "CONTAINS", "CONTAIN",
        "FORMULA", "INDIA", "LTD", "PVT", "LIMITED", "PHARMA", "PHARMACEUTICALS",
        "LABORATORIES", "LABS", "INCORPORATED", "INC",
        // Address, manufacturer and office noise words
        "AREA", "INDUSTRIAL", "ROAD", "STREET", "PLOT", "PHASE", "SECTOR", "BUILDING", "BLDG",
        "DIST", "DISTRICT", "PRADESH", "HIMACHAL", "PUNJAB", "HARYANA", "GUJARAT", "MAHARASHTRA",
        "DELHI", "KARNATAKA", "TAMIL", "NADU", "BENGAL", "BIHAR", "UTTAR", "KERALA", "OFFICE",
        "ADDRESS", "ADDR", "CUSTOMER", "CARE", "CONTACT", "PHONE", "TELEPHONE", "TEL", "EMAIL",
        "COMPLAINTS", "FEEDBACK", "TOLL", "FREE"
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
        val cleaned = NumericOcrCorrector.cleanOcrText(ocrText)
        val correctedOcr = NumericOcrCorrector.correct(cleaned)
        val upper = correctedOcr.uppercase(Locale.ROOT)
        val spaced = upper.replace(Regex("[^A-Z0-9 \\-]"), " ").replace(Regex("\\s+"), " ").trim()
        if (upper.contains("ALKALIZER") || upper.contains("ALKAFLOW")) return "ALKALIZER"

        DOLO_IN_TEXT.find(correctedOcr)?.let { return "DOLO 650" }

        normalizeKnownStrengthOcr(upper)?.let { return it }

        val compact = upper.replace(Regex("[^A-Z0-9]"), "")

        detectAlkalizerBrand(compact)?.let { return it }

        extractDoloBrand(compact, correctedOcr)?.let { return it }

        extractBrandStrengthFromCompact(spaced = spaced, compact = compact)?.let { return it }

        splitKnownFused(compact)?.let { return it }

        val fromIndex = findIndexedWordsInCompact(correctedOcr, compact)
        if (fromIndex.isNotBlank()) return fromIndex

        if (INGREDIENT_IN_TEXT.containsMatchIn(correctedOcr) &&
            !DOLO_IN_TEXT.containsMatchIn(correctedOcr) &&
            !DOLO_OCR.containsMatchIn(compact)
        ) {
            return ""
        }

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
        val correctedOcr = NumericOcrCorrector.correct(ocrText)
        Log.d(TAG, "RESOLVE: queries=$queries (from ${correctedOcr.take(80)}…)")

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
            bestMatches = Matcher.findTopMatches(correctedOcr, blacklist, maxResults)
            bestQuery = correctedOcr.take(40)
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
        val cleaned = NumericOcrCorrector.cleanOcrText(ocrText)
        val correctedOcr = NumericOcrCorrector.correct(cleaned)
        val primary = buildSearchQuery(correctedOcr)
        val list = mutableListOf<String>()
        if (primary.isNotBlank()) list.add(primary)

        val spacedForPairs = correctedOcr.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9 \\-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        extractAllBrandStrengthPairs(spacedForPairs).forEach { list.add(it) }

        val words = correctedOcr.uppercase(Locale.ROOT)
            .split(Regex("[^A-Z0-9]+"))
            .filter { it.isNotEmpty() }
        val hasDoloWord = words.any { it == "DOLO" || it == "DOLE" || it == "DELO" || it == "OLO" || it == "POLO" }
        val has650Word = words.any { it == "650" }

        val compact = correctedOcr.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
        if (has650Word && (DOLO_OCR.containsMatchIn(compact) || hasDoloWord)) {
            list.add("DOLO 650")
            list.add("DOLO 650MG TAB")
        }
        if (primary.isBlank() &&
            INGREDIENT_IN_TEXT.containsMatchIn(correctedOcr) &&
            has650Word
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

        // Add all individual brand-like tokens as queries
        val spaced = correctedOcr.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9 \\-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val tokens = spaced.split(" ")
            .map { it.trim() }
            .filter { isBrandToken(it) }
            .take(15)

        tokens.forEach { list.add(it) }

        for (i in 0 until tokens.size - 1) {
            list.add("${tokens[i]} ${tokens[i+1]}")
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
        val words = raw.uppercase(Locale.ROOT)
            .split(Regex("[^A-Z0-9]+"))
            .filter { it.isNotEmpty() }
        val hasDoloWord = words.any { it == "DOLO" || it == "DOLE" || it == "DELO" || it == "OLO" || it == "POLO" }
        val has650Word = words.any { it == "650" }
        if (hasDoloWord && has650Word) {
            return "DOLO 650"
        }
        return null
    }

    fun extractBrandStrengthFromCompact(spaced: String, compact: String): String? {
        return extractAllBrandStrengthPairs(spaced).firstOrNull()
    }

    fun extractAllBrandStrengthPairs(spaced: String): List<String> {
        val brandStrengthRegex = Regex(
            """\b([A-Z]{3,20})[\s\-]*((?:[2-9]00|1000|650|625|500|457|400|375|325|300|250|228|200|150|125|100|80|75|60|50|40|30|25|20|15|10|8|5|4|2))\b"""
        )
        return brandStrengthRegex.findAll(spaced)
            .map { match ->
                val brand    = match.groupValues[1]
                val strength = match.groupValues[2]
                if (brand.length >= 3 && brand !in INGREDIENT_WORDS) {
                    "$brand $strength"
                } else null
            }
            .filterNotNull()
            .toList()
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

    private fun findIndexedWordsInCompact(correctedOcr: String, compact: String): String {
        if (!MedicineRepository.isReady()) return ""

        val fuzzyDolo = DOLO_OCR.containsMatchIn(compact)
        val ocrTokens = Matcher.tokenize(correctedOcr).toSet()

        val hits = MedicineRepository.getIndex().keys
            .asSequence()
            .filter { word ->
                when {
                    word.length < 4 -> false
                    word in INGREDIENT_WORDS -> false
                    fuzzyDolo && word == "DOLO" -> true
                    word in ocrTokens -> true
                    word.length >= 6 && compact.contains(word) -> true
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
