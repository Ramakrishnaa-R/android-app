package com.fosautomations.pharmacam

import android.util.Log
import java.util.*
import kotlin.math.*

// ---------------------------------------------------------------------------
// ALIASES  — maps a canonical DB key → list of OCR variants / generic names
// Add more as you discover misses in scan_metrics.csv
// ---------------------------------------------------------------------------
private val ALIASES: Map<String, List<String>> = mapOf(
    "DOLO650"   to listOf("PARACETAMOL650", "DOLO", "DOLO650", "PARACETAMOL"),
    "AZTOR20"   to listOf("ATORVASTATIN20", "AZTOR", "ATORVASTATIN"),
    "LEVOKAST"  to listOf("MONTELUKAST", "LEVOCETIRIZINE", "LEVOKAST"),
    "NATRILAM5" to listOf("INDAPAMIDE", "AMLODIPINE", "NATRILAM")
)

object Matcher {

    // -----------------------------------------------------------------------
    // OCR character confusion map.
    // IMPORTANT: applied AFTER number extraction so dosage digits are safe.
    // -----------------------------------------------------------------------
    private val CHAR_FIXES = mapOf(
        '$' to 'S',
        '@' to 'A',
        '!' to 'I',
        '|' to 'I'
        // Deliberately NOT mapping 0→O, 1→I, 5→S, 7→T here because
        // those destroy dosage numbers before matching.
    )

    // -----------------------------------------------------------------------
    // Stopwords — tokens that carry zero brand-identity signal
    // -----------------------------------------------------------------------
    private val STOPWORDS = setOf(
        // dosage forms
        "TABLET", "TABLETS", "TAB", "TABS",
        "CAP", "CAPS", "CAPSULE", "CAPSULES",
        "SYRUP", "SUSPENSION", "INJECTION", "INJ",
        "TABLETSIP", "TABLETIP",
        // medical/regulatory
        "RELEASE", "PRESCRIPTION", "REGISTERED",
        "DOSAGE", "STORE", "STORAGE", "WARNING", "CAUTION",
        "KEEP", "REACH", "CHILDREN", "DIRECTED", "PHYSICIAN",
        "COMPOSITION", "CONTAINS", "CONTAIN",
        "FILM", "COATED", "UNCOATED", "BILAYERED",
        "EQUIVALENT", "EXCIPIENTS", "COLOUR", "COLOR",
        // pharmacopoeial designations
        "IP", "USP", "BP",
        // units (standalone)
        "MG", "ML", "GM", "MCG", "GRAM",
        // chemistry suffixes that pollute tokens
        "HYDROCHLORIDE", "SODIUM", "CHLORIDE", "ACETATE",
        // storage
        "COOL", "DRY", "PLACE", "PROTECT", "LIGHT", "MOISTURE",
        // filler
        "USE", "TAKE", "DAILY", "EACH", "DOSE",
        "BY", "OF", "THE", "AND", "OR", "TO", "IN",
        "AS", "FOR", "IS", "AN", "FROM", "WITH",
        // manufacturer noise
        "INDIA", "LTD", "PVT", "LIMITED",
        "PHARMA", "PHARMACEUTICALS", "LABORATORIES", "LABS",
        "MADE", "MANUFACTURED", "MFG", "REGD", "TRADE", "MARK",
        // OCR garbage
        "RX", "TION", "ABLE"
    )

    // -----------------------------------------------------------------------
    // Public result type
    // -----------------------------------------------------------------------
    data class ScoredMatch(
        val medicine: Medicine,
        val score: Double,
        val explanation: String
    )

    // =======================================================================
    // MAIN ENTRY POINT
    // =======================================================================
    fun findTopMatches(
        rawInput: String,
        blacklist: Set<String>,
        maxResults: Int = 3
    ): List<ScoredMatch> {

        Log.d("MATCHER", "========== MATCHING START ==========")
        Log.d("MATCHER", "Raw input: '$rawInput'")

        // --- Step 1: extract numbers from raw text BEFORE any normalization ---
        val inputNumbers = extractNumbers(rawInput)
        Log.d("MATCHER", "Input numbers (raw): $inputNumbers")

        // --- Step 2: light clean — remove special chars, collapse spaces ----
        val cleanInput = lightClean(rawInput)

        // --- Step 3: character-level OCR fixes (only non-digit confusions) --
        val fixedInput = applyCharFixes(cleanInput)

        // --- Step 4: uppercase + collapse whitespace -------------------------
        val normalizedInput = fixedInput.uppercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()

        Log.d("MATCHER", "Normalized: '$normalizedInput'")

        if (normalizedInput.length < 3) return emptyList()

        // --- Step 5: tokenize (filters stopwords, short tokens, pure digits) -
        val inputTokens = tokenize(normalizedInput)
        Log.d("MATCHER", "Tokens: $inputTokens")

        // Guard: if we have zero meaningful tokens, bail early
        if (inputTokens.isEmpty()) {
            Log.w("MATCHER", "No meaningful tokens after filtering")
            return emptyList()
        }

        val inputChars = normalizedInput.replace(" ", "")

        val database = MedicineRepository.getDatabase()

        val results = database.asSequence()
            .filter { it.name !in blacklist }
            .map { med ->
                scoreMedicine(med, inputTokens, inputChars, inputNumbers)
            }
            .filter { it.score >= 60.0 }          // lower gate; final sort will rank them
            .sortedByDescending { it.score }
            .distinctBy { it.medicine.name }
            .take(maxResults)
            .toList()

        results.forEachIndexed { i, m ->
            Log.d("MATCHER", "#${i + 1}: ${m.medicine.name} -> ${m.score.toInt()}% [${m.explanation}]")
        }

        return results
    }

    // =======================================================================
    // SCORING — one medicine vs the extracted input features
    // =======================================================================
    private fun scoreMedicine(
        med: Medicine,
        inputTokens: List<String>,
        inputChars: String,
        inputNumbers: Set<String>
    ): ScoredMatch {

        val medNameRaw  = med.name.uppercase(Locale.ROOT)
        val medNumbers  = extractNumbers(medNameRaw)
        val medTokens   = tokenize(medNameRaw)
        val medChars    = medNameRaw.replace(" ", "").replace(Regex("[^A-Z0-9]"), "")

        // Canonical key for alias lookup (strip spaces + hyphens)
        val medKey = medNameRaw.replace(Regex("[^A-Z0-9]"), "")
        val aliasTokens = ALIASES[medKey] ?: emptyList()

        val reasons = mutableListOf<String>()
        var score = 0.0

        // ===================================================================
        // GATE: require at least one token with meaningful similarity
        // This prevents pure-bigram false matches like Dolo→Head&Shoulders
        // ===================================================================
        val bestTokenSim = inputTokens.maxOfOrNull { input ->
            val directBest = medTokens.maxOfOrNull { similarity(input, it) } ?: 0.0
            val aliasBest  = aliasTokens.maxOfOrNull { similarity(input, it.uppercase()) } ?: 0.0
            max(directBest, aliasBest)
        } ?: 0.0

        if (bestTokenSim < 0.65) {
            // No token is even 65% similar — hard reject, skip scoring
            return ScoredMatch(med, 0.0, "GATE_FAIL(bestSim=${(bestTokenSim*100).toInt()}%)")
        }

        // ===================================================================
        // 1. TOKEN MATCH SCORE  (up to ~50 pts)
        // ===================================================================
        var tokenScore = 0.0

        for (input in inputTokens) {
            for (medToken in medTokens) {

                val sim = similarity(input, medToken)

                // Exact match
                if (input == medToken) {
                    val boost = lengthBoost(medToken)
                    tokenScore += boost
                    reasons.add("EXACT($input,+$boost)")
                    continue
                }

                // Alias match
                val aliasMatch = aliasTokens.any {
                    similarity(input, it.uppercase()) >= 0.88
                }
                if (aliasMatch) {
                    tokenScore += 10.0
                    reasons.add("ALIAS($input)")
                    continue
                }

                // Fuzzy match (only for tokens long enough to be meaningful)
                if (input.length >= 5 && medToken.length >= 5 && sim >= 0.80) {
                    val boost = (sim * lengthBoost(medToken)).coerceAtMost(12.0)
                    tokenScore += boost
                    reasons.add("FUZZY($input~$medToken,${(sim*100).toInt()}%)")
                }
            }
        }

        score += tokenScore

        // ===================================================================
        // 2. NUMBER MATCH / MISMATCH  (single block, no double-counting)
        // ===================================================================
        val commonNumbers = inputNumbers.intersect(medNumbers)

        when {
            // Both have numbers and they match → big bonus
            inputNumbers.isNotEmpty() && medNumbers.isNotEmpty() && commonNumbers.isNotEmpty() -> {
                score += 35.0
                reasons.add("NUM_MATCH($commonNumbers)")
            }
            // Both have numbers but they DON'T match → hard penalty
            inputNumbers.isNotEmpty() && medNumbers.isNotEmpty() && commonNumbers.isEmpty() -> {
                score -= 50.0
                reasons.add("NUM_MISMATCH(in=$inputNumbers,med=$medNumbers)")
            }
            // Only one side has numbers → mild penalty (OCR may have missed it)
            inputNumbers.isNotEmpty() && medNumbers.isEmpty() -> {
                score -= 10.0
                reasons.add("NUM_ORPHAN")
            }
        }

        // ===================================================================
        // 3. BRAND PREFIX BONUS
        // Token that is a prefix of the full medicine name → extra signal
        // ===================================================================
        for (token in inputTokens) {
            if (token.length >= 4 && medNameRaw.startsWith(token)) {
                score += 15.0
                reasons.add("BRAND_PREFIX($token)")
                break  // only count once
            }
        }

        // ===================================================================
        // 4. BIGRAM SIMILARITY  (character-level, lower weight)
        // Helps catch OCR errors in the middle of long brand names
        // ===================================================================
        val bigramScore = bigramSimilarity(inputChars, medChars) * 0.6
        score += bigramScore

        // ===================================================================
        // 5. SUBSEQUENCE SCORE  (very low weight, tie-breaker only)
        // ===================================================================
        val subseqScore = subsequenceScore(inputChars, medChars) * 0.15
        score += subseqScore

        // Cap at 100
        val finalScore = score.coerceIn(0.0, 100.0)

        return ScoredMatch(med, finalScore, reasons.joinToString(", "))
    }

    // =======================================================================
    // HELPERS
    // =======================================================================

    /** Points awarded for a token based on its length (longer = rarer = more signal) */
    private fun lengthBoost(token: String): Double = when {
        token.length >= 10 -> 18.0
        token.length >= 8  -> 14.0
        token.length >= 6  -> 10.0
        token.length >= 4  -> 6.0
        else               -> 2.0
    }

    /** Remove everything that isn't a letter, digit, or space */
    private fun lightClean(text: String): String =
        text.replace(Regex("[^A-Za-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Apply only non-digit character confusions */
    private fun applyCharFixes(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(CHAR_FIXES[ch] ?: ch)
        }
        return sb.toString()
    }

    /** Extract digit sequences from raw (un-normalized) text */
    private fun extractNumbers(text: String): Set<String> =
        Regex("\\d+").findAll(text).map { it.value }.toSet()

    /**
     * Tokenize: uppercase, split on spaces, then filter out:
     *  - length < 4
     *  - stopwords
     *  - pure digit strings (numbers handled separately)
     */
    private fun tokenize(text: String): List<String> =
        text.uppercase(Locale.ROOT)
            .split(" ")
            .map { it.trim() }
            .filter { token ->
                token.length >= 4 &&
                        token !in STOPWORDS &&
                        !token.all { it.isDigit() }
            }

    /** Levenshtein edit distance */
    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i-1][j] + 1, dp[i][j-1] + 1, dp[i-1][j-1] + cost)
            }
        }
        return dp[a.length][b.length]
    }

    /** Normalized similarity in [0, 1] */
    private fun similarity(a: String, b: String): Double {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    /** Bigram (character 2-gram) Jaccard similarity × 100 */
    private fun bigramSimilarity(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) return 0.0
        val aGrams = bigrams(a)
        val bGrams = bigrams(b)
        val intersection = aGrams.intersect(bGrams).size
        val union = (aGrams.size + bGrams.size - intersection).coerceAtLeast(1)
        return (intersection.toDouble() / union) * 100
    }

    private fun bigrams(text: String): Set<String> =
        (0 until text.length - 1).map { text.substring(it, it + 2) }.toSet()

    /** Fraction of input chars found as a subsequence in target, × 100 */
    private fun subsequenceScore(input: String, target: String): Double {
        if (input.isEmpty()) return 0.0
        var i = 0; var j = 0; var matched = 0
        while (i < input.length && j < target.length) {
            if (input[i] == target[j]) { matched++; i++ }
            j++
        }
        return (matched.toDouble() / input.length) * 100
    }
}