package com.fosautomations.pharmacam

import android.util.Log
import java.math.BigDecimal
import java.util.Locale

object NumericOcrCorrector {

    private const val TAG = "NumericOcrCorrector"

    private val BRAND_COLON_STRENGTH = Regex("""([A-Z])(:)(\d)""")
    private val BOUNDARY_LETTER_DIGIT = Regex("""([A-Z])(\d)""")
    private val BOUNDARY_DIGIT_LETTER = Regex("""(\d)([A-Z])""")
    private val SPACES = Regex("""\s+""")
    private val MULTIPLE_SPACES = Regex("""\s{2,}""")
    private val CANDIDATE_NUMBER = Regex("""\d+(\.\d+)?""")

    fun cleanOcrText(raw: String): String {
        var text = raw.uppercase(Locale.ROOT)

        // Strip pharmacy branding first — order matters (longest match first)
        val brandingPatterns = listOf(
            "PHARMEASY", "NARMEASY", "ARMEASY",  // PharmEasy variants
            "NETMEDS", "TATA1MG", "MEDLIFE", "APOLLO", "ZOYLO"
        )
        for (brand in brandingPatterns) {
            text = text.replace(brand, " ")
        }

        // Remove colons that OCR inserts between brand and strength (Raxo:20 → RAXO 20)
        // Do this BEFORE splitting so we don't corrupt digit sequences
        text = text.replace(BRAND_COLON_STRENGTH, "$1 $3")

        // Split letter-digit and digit-letter boundaries to get clean tokens
        text = text.replace(BOUNDARY_LETTER_DIGIT, "$1 $2")
        text = text.replace(BOUNDARY_DIGIT_LETTER, "$1 $2")

        // Apply word-level brand corrections on individual tokens
        val correctedTokens = text.split(SPACES).map { token ->
            when (token.trim()) {
                "RAXO", "RAX", "RAX0", "ROZO", "RAZOO", "REZO", "REBO", "RABOZ", "RABOA" -> "RAZO"
                "RABELPRAZOLE", "RABEPRAZOL", "RABEPRAZLE" -> "RABEPRAZOLE"
                else -> token
            }
        }

        return correctedTokens.joinToString(" ").replace(MULTIPLE_SPACES, " ").trim()
    }

    /** Hardcoded valid medicine strengths whitelist (all common doses). */
    private val VALID_STRENGTHS = setOf(
        "1", "2", "2.5", "3", "4", "5", "6", "8", "10",
        "12.5", "15", "20", "25", "30", "40", "50",
        "60", "75", "80", "90", "100", "110", "120",
        "125", "150", "180", "200", "220", "228",
        "250", "300", "333", "375", "400", "457",
        "500", "550", "600", "625", "650", "667",
        "750", "800", "850", "1000", "1200",
        "1500", "2000", "3000"
    )

    private val units = setOf("MG", "MCG", "G", "GM", "KG", "ML", "L", "IU", "%")
    private val tokenRegex = Regex("""[A-Za-z0-9.]+%?|[^A-Za-z0-9.]+""")
    private val wordRegex = Regex("""^[A-Za-z0-9.]+%?$""")

    /**
     * Correct OCR numeric mistakes in medicine strengths.
     * Converts: DOLO GSO → DOLO 650, AZEE SOO → AZEE 500, etc.
     * 
     * Pipeline flow:
     * OCR Text → normalize → correct → validate → search query → matcher
     */
    fun correct(text: String): String {
        if (text.isBlank()) return text
        
        Log.d(TAG, "Original OCR: $text")
        
        val tokens = tokenRegex.findAll(text).map { it.value }.toList()
        val result = buildString {
            tokens.forEachIndexed { index, token ->
                append(
                    if (wordRegex.matches(token)) {
                        correctToken(
                            token = token,
                            previousWord = nearestWord(tokens, index, -1),
                            nextWord = nearestWord(tokens, index, 1)
                        )
                    } else {
                        token
                    }
                )
            }
        }
        
        val corrected = result.replace(MULTIPLE_SPACES, " ").trim()
        
        if (corrected != text) {
            Log.d(TAG, "Final corrected OCR: $corrected")
        }
        
        return corrected
    }

    private fun correctToken(token: String, previousWord: String?, nextWord: String?): String {
        val split = splitToken(token) ?: return token
        if (!looksLikeNumericOcr(split.numeric)) return token

        val candidate = buildString {
            split.numeric.forEach { ch ->
                append(
                    when (ch) {
                        'G' -> '6'
                        'g' -> '9'
                        'S', 's' -> '5'
                        'O', 'o' -> '0'
                        'I', 'l', 'L' -> '1'
                        'B' -> '8'
                        'Z' -> '2'
                        else -> ch
                    }
                )
            }
        }
        
        if (!candidate.matches(CANDIDATE_NUMBER)) return token

        val corrected = normalizeNumber(candidate)
        
        // Validation: check if corrected value is in whitelist
        if (corrected !in VALID_STRENGTHS) {
            Log.d(TAG, "Numeric candidate '$corrected' NOT in whitelist (from '$token'), rejecting")
            return token
        }
        
        val hasUnitContext = split.unit.isNotEmpty() || isUnit(previousWord) || isUnit(nextWord)
        if (!hasUnitContext && !isStandaloneStrengthCandidate(split.numeric, corrected)) {
            Log.d(TAG, "No unit context for '$token', rejecting")
            return token
        }

        Log.d(TAG, "Numeric correction: '$token' → '${split.numericPrefix}$corrected${split.unit}${split.suffix}' (valid)")
        return "${split.numericPrefix}$corrected${split.unit}${split.suffix}"
    }

    private data class TokenParts(
        val numericPrefix: String,
        val numeric: String,
        val unit: String,
        val suffix: String
    )

    private fun splitToken(token: String): TokenParts? {
        var core = token
        var suffix = ""
        if (core.endsWith("%")) {
            core = core.dropLast(1)
            suffix = "%"
        }

        var unit = ""
        val upper = core.uppercase(Locale.ROOT)
        units.filter { it != "%" }
            .sortedByDescending { it.length }
            .firstOrNull { upper.endsWith(it) && core.length > it.length }
            ?.let { found ->
                unit = found
                core = core.dropLast(found.length)
            }

        if (core.isBlank()) return null
        return TokenParts(numericPrefix = "", numeric = core, unit = unit, suffix = suffix)
    }

    private fun looksLikeNumericOcr(value: String): Boolean {
        val chars = value.filter { it != '.' }
        if (chars.isEmpty()) return false
        return chars.all { ch ->
            ch.isDigit() || ch in setOf('G', 'g', 'S', 's', 'O', 'o', 'I', 'l', 'L', 'B', 'Z')
        }
    }

    private fun isStandaloneStrengthCandidate(original: String, corrected: String): Boolean {
        if (original.length > 4) return false
        if (original.length == 1 && original != corrected) return false
        return original != corrected || original.all { it.isDigit() }
    }

    private fun nearestWord(tokens: List<String>, index: Int, step: Int): String? {
        var cursor = index + step
        while (cursor in tokens.indices) {
            val token = tokens[cursor]
            if (wordRegex.matches(token)) return token
            cursor += step
        }
        return null
    }

    private fun isUnit(token: String?): Boolean =
        token?.uppercase(Locale.ROOT)?.trimEnd('.') in units

    private fun normalizeNumber(value: String): String {
        val decimal = BigDecimal(value).stripTrailingZeros()
        return decimal.toPlainString()
    }
}
