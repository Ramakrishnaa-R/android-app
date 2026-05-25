package com.fosautomations.pharmacam

import android.util.Log
import java.util.*
import kotlin.math.min
import kotlin.math.max

object Matcher {

    private val CHARACTER_NORMALIZATION = mapOf(
        'ð' to 'D', '0' to 'O', '1' to 'I', '5' to 'S', '8' to 'B',
        '|' to ' ', '!' to 'I', '$' to 'S', '7' to 'T'
    )

    data class ScoredMatch(val medicine: Medicine, val score: Double, val explanation: String)

    fun findTopMatches(
        rawInput: String,
        blacklist: Set<String>,
        maxResults: Int = 3
    ): List<ScoredMatch> {

        Log.d("MATCHER_DEBUG", "========== MATCHING START ==========")
        Log.d("MATCHER_DEBUG", "Raw input: '$rawInput'")

        val normalizedInput = normalizeText(rawInput)
        Log.d("MATCHER_DEBUG", "Normalized: '$normalizedInput'")

        if (normalizedInput.length < 1) {
            Log.e("MATCHER_DEBUG", "Input too short after normalization!")
            return emptyList()
        }

        val inputTokens = tokenize(normalizedInput)
        val inputChars = normalizedInput.replace(" ", "")
        val inputNumbers = extractNumbers(normalizedInput)
        val inputPhonetic = toPhonetic(normalizedInput)

        Log.d("MATCHER_DEBUG", "Tokens: $inputTokens")
        Log.d("MATCHER_DEBUG", "Numbers: $inputNumbers")
        Log.d("MATCHER_DEBUG", "Chars: $inputChars")

        val candidates = shortlistCandidates(inputTokens, inputChars, inputNumbers, blacklist)
        Log.d("MATCHER_DEBUG", "Candidates shortlisted: ${candidates.size} / ${MedicineRepository.getDatabase().size}")

        val results = candidates
            .asSequence()
            .map { med -> scoreMedicine(med, inputTokens, inputChars, inputNumbers, inputPhonetic) }
            .filter { it.score > 15.0 } // Minimum threshold
            .sortedByDescending { it.score }
            .distinctBy { it.medicine.name }
            .take(maxResults)
            .toList()

        Log.d("MATCHER_DEBUG", "Found ${results.size} matches above threshold")
        results.forEachIndexed { index, match ->
            Log.d("MATCHER_DEBUG", "#${index + 1}: ${match.medicine.name} | Score: ${match.score.toInt()} | ${match.explanation}")
        }
        Log.d("MATCHER_DEBUG", "========== MATCHING END ==========")

        return results
    }

    private fun shortlistCandidates(
        inputTokens: List<String>,
        inputChars: String,
        inputNumbers: Set<String>,
        blacklist: Set<String>
    ): List<Medicine> {
        val database = MedicineRepository.getDatabase()
        val index = MedicineRepository.getIndex()
        if (database.isEmpty()) return emptyList()
        if (inputTokens.isEmpty() && inputChars.length < 3) return database.filterNotBlacklisted(blacklist)

        val scores = linkedMapOf<Medicine, Int>()
        fun addCandidate(medicine: Medicine, points: Int) {
            if (medicine.name in blacklist) return
            scores[medicine] = (scores[medicine] ?: 0) + points
        }

        val signalTokens = inputTokens
            .filter { it.length >= 3 && !it.all(Char::isDigit) }
            .distinct()

        signalTokens.forEach { token ->
            index[token]?.forEach { addCandidate(it, 120) }

            index.entries
                .asSequence()
                .filter { (indexedToken, _) -> indexedToken.startsWith(token) || token.startsWith(indexedToken) }
                .flatMap { it.value.asSequence() }
                .take(PREFIX_CANDIDATE_LIMIT)
                .forEach { addCandidate(it, 70) }

            if (token.length >= 4) {
                index.entries
                    .asSequence()
                    .filter { (indexedToken, _) ->
                        indexedToken.length >= 4 &&
                            kotlin.math.abs(indexedToken.length - token.length) <= 1 &&
                            levenshtein(indexedToken, token) <= 1
                    }
                    .flatMap { it.value.asSequence() }
                    .take(FUZZY_CANDIDATE_LIMIT)
                    .forEach { addCandidate(it, 45) }
            }
        }

        if (inputNumbers.isNotEmpty()) {
            database.asSequence()
                .filter { it.name !in blacklist }
                .filter { med -> inputNumbers.any { number -> med.name.contains(number) } }
                .take(NUMBER_CANDIDATE_LIMIT)
                .forEach { addCandidate(it, 25) }
        }

        if (scores.isEmpty()) {
            return database.filterNotBlacklisted(blacklist)
        }

        val strongCandidates = scores.entries
            .sortedByDescending { it.value }
            .take(MAX_SHORTLIST_SIZE)
            .map { it.key }

        return strongCandidates.ifEmpty { database.filterNotBlacklisted(blacklist) }
    }

    private fun scoreMedicine(
        med: Medicine,
        inputTokens: List<String>,
        inputChars: String,
        inputNumbers: Set<String>,
        inputPhonetic: String
    ): ScoredMatch {
        val medName = med.name.uppercase(Locale.ROOT)
        val medTokens = tokenize(medName)
        val medChars = medName.replace(" ", "")
        val medNumbers = extractNumbers(medName)
        val medPhonetic = toPhonetic(medName)

        var totalScore = 0.0
        val reasons = mutableListOf<String>()

        // Strategy 1: Token Overlap (40% weight)
        val tokenScore = calculateTokenOverlap(inputTokens, medTokens)
        totalScore += tokenScore * 0.40
        if (tokenScore > 0) reasons.add("Token:${tokenScore.toInt()}")

        // Strategy 2: Character N-grams (25% weight)
        val ngramScore = calculateNgramSimilarity(inputChars, medChars)
        totalScore += ngramScore * 0.25
        if (ngramScore > 0) reasons.add("Ngram:${ngramScore.toInt()}")

        // Strategy 3: Subsequence Match (15% weight)
        val subseqScore = calculateSubsequenceScore(inputChars, medChars)
        totalScore += subseqScore * 0.15
        if (subseqScore > 0) reasons.add("Subseq:${subseqScore.toInt()}")

        // Strategy 4: Number Match (15% weight)
        val numberScore = if (inputNumbers.isNotEmpty()) {
            (inputNumbers.intersect(medNumbers).size.toDouble() / inputNumbers.size) * 100
        } else 0.0
        totalScore += numberScore * 0.15
        if (numberScore > 0) reasons.add("Num:${numberScore.toInt()}")

        // Strategy 5: Phonetic Similarity (5% weight)
        val phoneticScore = calculatePhoneticSimilarity(inputPhonetic, medPhonetic)
        totalScore += phoneticScore * 0.05
        if (phoneticScore > 0) reasons.add("Phone:${phoneticScore.toInt()}")

        return ScoredMatch(med, totalScore, reasons.joinToString(", "))
    }

    private fun List<Medicine>.filterNotBlacklisted(blacklist: Set<String>): List<Medicine> {
        return filter { it.name !in blacklist }
    }

    // ==================== STRATEGY 1: Token Overlap ====================
    private fun calculateTokenOverlap(inputTokens: List<String>, medTokens: List<String>): Double {
        if (inputTokens.isEmpty() || medTokens.isEmpty()) return 0.0

        var matchCount = 0
        for (inputToken in inputTokens) {
            for (medToken in medTokens) {
                if (inputToken == medToken || levenshtein(inputToken, medToken) <= 1) {
                    matchCount++
                    break
                }
            }
        }

        // Coverage: what % of medicine tokens were matched
        return (matchCount.toDouble() / medTokens.size) * 100
    }

    // ==================== STRATEGY 2: Character N-grams ====================
    private fun calculateNgramSimilarity(input: String, target: String): Double {
        if (input.length < 2 || target.length < 2) return 0.0

        val inputBigrams = getBigrams(input)
        val targetBigrams = getBigrams(target)

        val intersection = inputBigrams.intersect(targetBigrams).size
        val union = (inputBigrams.size + targetBigrams.size - intersection).coerceAtLeast(1)

        return (intersection.toDouble() / union) * 100
    }

    private fun getBigrams(text: String): Set<String> {
        return (0 until text.length - 1).map { text.substring(it, it + 2) }.toSet()
    }

    // ==================== STRATEGY 3: Subsequence Matching ====================
    private fun calculateSubsequenceScore(input: String, target: String): Double {
        if (input.isEmpty()) return 0.0

        var i = 0
        var j = 0
        var matchedChars = 0

        while (i < input.length && j < target.length) {
            if (input[i] == target[j]) {
                matchedChars++
                i++
            }
            j++
        }

        // What % of input characters appear in order in target
        return (matchedChars.toDouble() / input.length) * 100
    }

    // ==================== STRATEGY 4: Number Extraction ====================
    private fun extractNumbers(text: String): Set<String> {
        return text.split(" ").filter { it.all { c -> c.isDigit() } }.toSet()
    }

    // ==================== STRATEGY 5: Phonetic Similarity ====================
    private fun toPhonetic(text: String): String {
        // Simplified Soundex-like phonetic encoding
        val cleaned = text.replace("[^A-Z]".toRegex(), "")
        if (cleaned.isEmpty()) return ""

        val phonetic = StringBuilder()
        phonetic.append(cleaned[0])

        val codeMap = mapOf(
            'B' to '1', 'F' to '1', 'P' to '1', 'V' to '1',
            'C' to '2', 'G' to '2', 'J' to '2', 'K' to '2', 'Q' to '2', 'S' to '2', 'X' to '2', 'Z' to '2',
            'D' to '3', 'T' to '3',
            'L' to '4',
            'M' to '5', 'N' to '5',
            'R' to '6'
        )

        var prevCode = codeMap[cleaned[0]] ?: '0'
        for (i in 1 until cleaned.length) {
            val code = codeMap[cleaned[i]] ?: '0'
            if (code != '0' && code != prevCode) {
                phonetic.append(code)
            }
            prevCode = code
        }

        return phonetic.toString()
    }

    private fun calculatePhoneticSimilarity(input: String, target: String): Double {
        if (input.isEmpty() || target.isEmpty()) return 0.0
        val distance = levenshtein(input, target)
        val maxLen = max(input.length, target.length)
        return ((maxLen - distance).toDouble() / maxLen) * 100
    }

    // ==================== UTILITIES ====================
    private fun levenshtein(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
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
        return text.uppercase(Locale.ROOT)
            .replace("DOLLAR", "DOLO")
            .replace("MICHAEL", "DOLO")
            .replace("$", "S")
            .replace("[^A-Z0-9 ]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun tokenize(text: String): List<String> {
        return text.split(" ").filter { it.isNotEmpty() }
    }

    private const val MAX_SHORTLIST_SIZE = 700
    private const val PREFIX_CANDIDATE_LIMIT = 350
    private const val FUZZY_CANDIDATE_LIMIT = 250
    private const val NUMBER_CANDIDATE_LIMIT = 500
}
