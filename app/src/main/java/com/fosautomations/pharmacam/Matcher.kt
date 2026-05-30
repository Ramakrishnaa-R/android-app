package com.fosautomations.pharmacam

import android.util.Log
import java.util.*
import kotlin.math.max
import kotlin.math.min

object Matcher {

    private val CHARACTER_NORMALIZATION = mapOf(
        '0' to 'O',
        '1' to 'I',
        '5' to 'S',
        '8' to 'B',
        '$' to 'S',
        '7' to 'T'
    )

    private val STOPWORDS = setOf(
        "TABLET",
        "TABLETS",
        "TAB",
        "TABS",
        "CAP",
        "CAPSULE",
        "CAPSULES",
        "DOSAGE",
        "STORE",
        "INDIA",
        "WARNING",
        "CHILDREN",
        "DIRECTED",
        "COOL",
        "DRY",
        "PLACE",
        "MG",
        "ML",
        "IP",
        "USE",
        "KEEP",
        "REACH",
        "TAKE",
        "DAILY",
        "BY",
        "OF",
        "THE",
        "AND",
        "OR",
        "TO",
        "IN",
        "AS",
        "PHYSICIAN",
        "COMPOSITION",
        "CONTAINS",
        "FILM",
        "COATED",
        "TABLETSIP",
        "HYDROCHLORIDE",
        "SODIUM"
    )

    data class ScoredMatch(
        val medicine: Medicine,
        val score: Double,
        val explanation: String
    )

    fun findTopMatches(
        rawInput: String,
        blacklist: Set<String>,
        maxResults: Int = 3
    ): List<ScoredMatch> {

        Log.d("MATCHER_DEBUG", "========== MATCHING START ==========")
        Log.d("MATCHER_DEBUG", "Raw input: '$rawInput'")

        val normalizedInput = normalizeText(rawInput)

        Log.d("MATCHER_DEBUG", "Normalized: '$normalizedInput'")

        if (normalizedInput.length < 3) {
            return emptyList()
        }

        val inputTokens = tokenize(normalizedInput)
        val inputChars = normalizedInput.replace(" ", "")
        val inputNumbers = extractNumbers(normalizedInput)

        Log.d("MATCHER_DEBUG", "Tokens: $inputTokens")

        val database = MedicineRepository.getDatabase()

        val results = database.asSequence()

            .filter { it.name !in blacklist }

            .map {
                scoreMedicine(
                    it,
                    inputTokens,
                    inputChars,
                    inputNumbers
                )
            }

            .filter { it.score >= 70 }

            .sortedByDescending { it.score }

            .distinctBy { it.medicine.name }

            .take(maxResults)

            .toList()

        results.forEachIndexed { index, match ->
            Log.d(
                "MATCHER_DEBUG",
                "#${index + 1}: ${match.medicine.name} -> ${match.score}"
            )
        }

        return results
    }

    private fun scoreMedicine(
        med: Medicine,
        inputTokens: List<String>,
        inputChars: String,
        inputNumbers: Set<String>
    ): ScoredMatch {

        val medName = normalizeText(med.name)

        val medTokens = tokenize(medName)
        val medChars = medName.replace(" ", "")
        val medNumbers = extractNumbers(medName)

        var score = 0.0

        val reasons = mutableListOf<String>()

        // =========================
        // EXACT TOKEN MATCH
        // =========================

        var exactMatches = 0

        inputTokens.forEach { input ->

            medTokens.forEach { medToken ->

                if (input == medToken) {
                    exactMatches++
                }
            }
        }

        if (exactMatches > 0) {

            val exactScore = exactMatches * 35.0

            score += exactScore

            reasons.add("Exact:$exactMatches")
        }

        // =========================
        // FUZZY TOKEN MATCH
        // =========================

        var fuzzyMatches = 0

        inputTokens.forEach { input ->

            medTokens.forEach { medToken ->

                if (
                    input != medToken &&
                    input.length >= 4 &&
                    levenshtein(input, medToken) <= 2
                ) {
                    fuzzyMatches++
                }
            }
        }

        if (fuzzyMatches > 0) {

            val fuzzyScore = fuzzyMatches * 15.0

            score += fuzzyScore

            reasons.add("Fuzzy:$fuzzyMatches")
        }

        // =========================
        // BIGRAM SCORE
        // =========================

        val bigram = calculateNgramSimilarity(
            inputChars,
            medChars
        )

        score += bigram * 0.45

        // =========================
        // SUBSEQUENCE
        // =========================

        val subseq = calculateSubsequenceScore(
            inputChars,
            medChars
        )

        score += subseq * 0.15

        // =========================
        // NUMBER MATCH
        // =========================

        if (
            inputNumbers.isNotEmpty() &&
            medNumbers.isNotEmpty()
        ) {

            val common = inputNumbers.intersect(medNumbers)

            if (common.isNotEmpty()) {

                score += 25.0

                reasons.add("Number")
            }
        }

        // =========================
        // BRAND PRIORITY BONUS
        // =========================

        inputTokens.forEach { token ->

            if (
                token.length >= 4 &&
                medName.startsWith(token)
            ) {

                score += 20

                reasons.add("Brand")
            }
        }

        val finalScore = min(score, 100.0)

        return ScoredMatch(
            med,
            finalScore,
            reasons.joinToString(", ")
        )
    }

    private fun calculateNgramSimilarity(
        input: String,
        target: String
    ): Double {

        if (input.length < 2 || target.length < 2) {
            return 0.0
        }

        val inputBigrams = getBigrams(input)
        val targetBigrams = getBigrams(target)

        val intersection =
            inputBigrams.intersect(targetBigrams).size

        val union =
            (inputBigrams.size + targetBigrams.size - intersection)
                .coerceAtLeast(1)

        return (intersection.toDouble() / union) * 100
    }

    private fun getBigrams(text: String): Set<String> {

        return (0 until text.length - 1)
            .map {
                text.substring(it, it + 2)
            }
            .toSet()
    }

    private fun calculateSubsequenceScore(
        input: String,
        target: String
    ): Double {

        if (input.isEmpty()) {
            return 0.0
        }

        var i = 0
        var j = 0
        var matched = 0

        while (i < input.length && j < target.length) {

            if (input[i] == target[j]) {
                matched++
                i++
            }

            j++
        }

        return (matched.toDouble() / input.length) * 100
    }

    private fun extractNumbers(text: String): Set<String> {

        return Regex("\\d+")
            .findAll(text)
            .map { it.value }
            .toSet()
    }

    private fun levenshtein(
        s1: String,
        s2: String
    ): Int {

        val dp = Array(s1.length + 1) {
            IntArray(s2.length + 1)
        }

        for (i in 0..s1.length) {
            dp[i][0] = i
        }

        for (j in 0..s2.length) {
            dp[0][j] = j
        }

        for (i in 1..s1.length) {

            for (j in 1..s2.length) {

                val cost =
                    if (s1[i - 1] == s2[j - 1]) 0 else 1

                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }

        return dp[s1.length][s2.length]
    }

    private fun normalizeText(text: String): String {

        var normalized =
            text.uppercase(Locale.ROOT)

        CHARACTER_NORMALIZATION.forEach { (wrong, correct) ->

            normalized =
                normalized.replace(wrong, correct)
        }

        normalized = normalized

            .replace("-", "")
            .replace("_", "")
            .replace("[^A-Z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

        return normalized
    }

    private fun tokenize(text: String): List<String> {

        return text
            .split(" ")

            .map { it.trim() }

            .filter {

                it.isNotEmpty() &&
                        it.length >= 3 &&
                        it !in STOPWORDS &&
                        !it.all(Char::isDigit)
            }
    }
}