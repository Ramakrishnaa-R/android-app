package com.fosautomations.pharmacam

import android.util.Log
import java.util.*
import kotlin.math.*

// ---------------------------------------------------------------------------
// ALIASES — generic ingredient names that map to a brand
// ---------------------------------------------------------------------------
private val ALIASES: Map<String, List<String>> = mapOf(
    "DOLO"       to listOf("PARACETAMOL", "PAROCETAMOL", "PUROCETAMOL", "PUROCETOMOL", "FUROCETOMOL", "FUROCETAMOL", "ACETAMINOPHEN", "DOLO-650", "DOLO650", "DOLE650"),
    "DOLOPAR"    to listOf("PARACETAMOL", "DOLO-650", "DOLO650"),
    "CROCIN"     to listOf("PARACETAMOL", "ACETAMINOPHEN"),
    "CALPOL"     to listOf("PARACETAMOL", "ACETAMINOPHEN"),
    "AZTOR"      to listOf("ATORVASTATIN", "ATORVASTATIN CALCIUM", "LIPITOR", "AZTOR20"),
    "ATORVA"     to listOf("ATORVASTATIN"),
    "LIPVAS"     to listOf("ATORVASTATIN"),
    "STORVAS"    to listOf("ATORVASTATIN"),
    "LEVOKAST"   to listOf("MONTELUKAST", "LEVOCETIRIZINE", "LEVOCETRIZINE"),
    "MONTAIR"    to listOf("MONTELUKAST", "LEVOCETIRIZINE", "LEVOCETRIZINE"),
    "MONTEK"     to listOf("MONTELUKAST", "LEVOCETIRIZINE"),
    "TELEKAST"   to listOf("MONTELUKAST", "LEVOCETIRIZINE"),
    "ODIMONT"    to listOf("MONTELUKAST", "LEVOCETIRIZINE"),
    "NATRILAM"   to listOf("INDAPAMIDE", "AMLODIPINE"),
    "AMLODAC"    to listOf("AMLODIPINE"),
    "AMLIP"      to listOf("AMLODIPINE"),
    "STAMLO"     to listOf("AMLODIPINE"),
    "GLYCOMET"   to listOf("METFORMIN"),
    "GLUCOMET"   to listOf("METFORMIN"),
    "OBIMET"     to listOf("METFORMIN"),
    "OMEZ"       to listOf("OMEPRAZOLE", "PANTOPRAZOLE", "RABEPRAZOLE"),
    "PANTOP"     to listOf("PANTOPRAZOLE"),
    "PANTOCID"   to listOf("PANTOPRAZOLE"),
    "CETZINE"    to listOf("CETIRIZINE"),
    "OKACET"     to listOf("CETIRIZINE"),
    "ALERID"     to listOf("CETIRIZINE"),
    "ALMOX"      to listOf("AMOXICILLIN", "AMOXYCILLIN"),
    "NOVAMOX"    to listOf("AMOXICILLIN", "AMOXYCILLIN"),
    "MOXIKIND"   to listOf("AMOXICILLIN", "AMOXYCILLIN", "CLAVULANATE"),
    "AZEE"       to listOf("AZITHROMYCIN"),
    "AZITHRAL"   to listOf("AZITHROMYCIN"),
    "RAZO"       to listOf("RABEPRAZOLE", "ROZO"),
    "RABELOC"    to listOf("RABEPRAZOLE"),
    "RABLET"     to listOf("RABEPRAZOLE"),
    "ALKALIZER"  to listOf("ALKAZAR", "ALKAFLOW", "LIAFLOR"),
    "ALKOF"      to listOf("COFGELS", "COFGEL"),
    "TBACT"      to listOf("MUPIROCIN", "MUPROCIN", "MUPTROCIN"),
    "TGEL"       to listOf("TAZAROTENE"),
    "TRET"       to listOf("TRETINOIN"),
)

private val OCR_WORD_CORRECTIONS = mapOf(
    "TOBLET" to "TABLET", "TOBLETS" to "TABLET", "TOHLET" to "TABLET",
    "TOHLETS" to "TABLET", "TABLST" to "TABLET", "TABIET" to "TABLET",
    "TABTET" to "TABLET", "TABLEL" to "TABLET", "TABT" to "TABLET",
    "TABLEIS" to "TABLET", "TAOLETS" to "TABLET", "TUBLET" to "TABLET",
    "TAHLOT" to "TABLET", "TATIET" to "TABLET", "TATLET" to "TABLET",
    "TABLAT" to "TABLET", "TATLELS" to "TABLET", "ABLET" to "TABLET",
    "ABIET" to "TABLET", "ABLST" to "TABLET", "LOBLETS" to "TABLETS",
    "YABLETS" to "TABLETS", "TAHLETS" to "TABLETS", "TEBLET" to "TABLET",
    "TEBLETS" to "TABLETS",
    "UNCOETED" to "UNCOATED", "UNCOATOD" to "UNCOATED", "UNCOABED" to "UNCOATED",
    "UNCOTD" to "UNCOATED", "UNCOTED" to "UNCOATED", "UNCOATD" to "UNCOATED",
    "UNCCATOD" to "UNCOATED", "UNCOABAD" to "UNCOATED", "UNCOSTED" to "UNCOATED",
    "CONTALNS" to "CONTAINS", "CONTALIS" to "CONTAINS", "CONTSLIS" to "CONTAINS",
    "CONTANS" to "CONTAINS", "CONTAIS" to "CONTAINS", "OONTALNS" to "CONTAINS",
    "OONTAINS" to "CONTAINS", "OONTALNA" to "CONTAINS", "COSTALNS" to "CONTAINS",
    "CANTAINS" to "CONTAINS", "CUNTEINS" to "CONTAINS", "COTINS" to "CONTAINS",
    "COALNS" to "CONTAINS", "RONTAINS" to "CONTAINS", "ONTEINS" to "CONTAINS",
    "OORTANS" to "CONTAINS", "ORTALNS" to "CONTAINS", "CORTALNS" to "CONTAINS",
    "STORIG" to "STORAGE", "STORS" to "STORE", "STOR" to "STORE",
    "DIRECTOD" to "DIRECTED", "DIRSCTED" to "DIRECTED", "DIRCCTCD" to "DIRECTED",
    "DIRCTEC" to "DIRECTED", "DIRECTC" to "DIRECTED", "DRECTED" to "DIRECTED",
    "PHRGSCAN" to "PHYSICIAN", "PRYSCIAN" to "PHYSICIAN", "PHYSCIAN" to "PHYSICIAN",
    "PHYSTCIAN" to "PHYSICIAN",
    "KEOP" to "KEEP", "KEAP" to "KEEP", "KOEP" to "KEEP",
    "CHLDREN" to "CHILDREN", "CHLLDREN" to "CHILDREN", "CHLOREN" to "CHILDREN",
    "CHLDRAN" to "CHILDREN", "CHLLDRAN" to "CHILDREN",
    "PROTEOT" to "PROTECT", "PROT" to "PROTECT",
    "LLGHT" to "LIGHT", "LGHT" to "LIGHT",
    "MOLSTURE" to "MOISTURE", "NOLSTURE" to "MOISTURE", "MOLSTUE" to "MOISTURE",
    "MOSTURE" to "MOISTURE", "NOLSTUE" to "MOISTURE",
    "DOSAGA" to "DOSAGE", "DOSAGS0" to "DOSAGE", "DOSAC" to "DOSAGE",
    "DOSSGE" to "DOSAGE", "DOSAYE" to "DOSAGE",
    "MEDLCINE" to "MEDICINE", "MODICINE" to "MEDICINE",
    "BEGISTEED" to "REGISTERED", "REGISTSRO" to "REGISTERED",
    "BEGISLERD" to "REGISTERED", "OBGISTARD" to "REGISTERED",
    "REGISTERE" to "REGISTERED", "REGSTERED" to "REGISTERED",
    "ANALGESC" to "ANALGESIC", "ANALGESLC" to "ANALGESIC",
    "PLCE" to "PLACE", "PLICO" to "PLACE", "PLICE" to "PLACE",
    "BALOW" to "BELOW", "BEOW" to "BELOW",
    "DELOO" to "DOLO", "DELO" to "DOLO", "DOLO0" to "DOLO",
    "POLOGS" to "DOLO", "POLO" to "DOLO", "DOLE" to "DOLO",
    "DOLS" to "DOLO", "DOLG" to "DOLO", "DOLB" to "DOLO",
    "DYLO" to "DOLO",
    "ALKAFLOW" to "ALKALIZER",
    "LIAFLOR" to "ALKALIZER",
    "RAXO" to "RAZO",
    "RAX" to "RAZO",
    "RAX0" to "RAZO",
    "ROZO" to "RAZO",
    "REZO" to "RAZO",
    "REBO" to "RAZO",
    "NARMEASY" to "",
    "ARMEASY" to "",
    "PHARMEASY" to "",
    "NETMEDS" to "",
)

private val VOWELS = setOf('A', 'E', 'I', 'O', 'U')

// FIX 3: Lowered vowel floor from 0.15 → 0.10.
// 0.15 incorrectly drops real short brands: GLYCOMET=12.5%, AZTOR=20% (marginal),
// AZEE=50% (safe). The real gibberish guard is already handled by the length≥3
// filter and STOPWORDS. A 10% floor only kills truly consonant-only strings.
private fun isGibberish(token: String): Boolean {
    if (token.length < 3) return true
    val vowelCount = token.count { it in VOWELS }
    val vowelRatio = vowelCount.toDouble() / token.length
    return vowelRatio < 0.10
}

// ---------------------------------------------------------------------------
// TOKEN FUSION
// Merges short-prefix tokens (< 3 chars) with the next token BEFORE the
// length filter runs. This preserves hyphenated brands like:
//   T-BACT  → ["T","BACT"] → fuse → also add "TBACT"
//   A-RET   → ["A","RET"]  → fuse → also add "ARET"
//   A-TO-Z  → ["A","TO","Z"] → fuse → also add "ATOZ"
// The original parts are still kept so they participate in normal scoring.
// ---------------------------------------------------------------------------
private fun fuseShortTokens(tokens: List<String>): List<String> {
    val result = mutableListOf<String>()
    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        if (token.length < 3 && i + 1 < tokens.size) {
            val next = tokens[i + 1]
            if (!next.all { it.isDigit() } && !token.all { it.isDigit() }) {
                // Two-token fusion: T + BACT → TBACT
                result.add(token + next)
                // Three-token fusion: A + TO + Z → ATOZ
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

// ---------------------------------------------------------------------------
// STOPWORDS
// FIX 4: Removed "INJECTION", "SYRUP", "SUSPENSION" — these are category
// signals that hurt scoring when stripped (e.g. OCR reads "CEFTRIAXONE INJECTION
// 1G" — stripping INJECTION loses a useful category confirmation token).
// They are still useless for brand disambiguation, so we handle them by scoring
// them separately as category-confirmation tokens, not stripping them entirely.
//
// FIX 5: Removed MG, ML, MCG from STOPWORDS. These are needed by
// extractDosageSuffixes(). The old code stripped them in tokenize() so
// extractDosageSuffixes() always returned empty for those units.
// They are still not useful as standalone brand tokens so they won't
// pollute token scoring — extractDosageSuffixes() runs on the raw joined
// token string before the stopword filter is applied.
// ---------------------------------------------------------------------------
private val STOPWORDS = setOf(
    "TABLET", "TABLETS", "TAB", "TABS",
    "CAP", "CAPS", "CAPSULE", "CAPSULES",
    // REMOVED: "SYRUP", "SUSPENSION", "INJECTION", "INJ" — see FIX 4
    "TABLETSIP", "TABLETIP",
    "RELEASE", "PRESCRIPTION", "REGISTERED",
    "DOSAGE", "STORE", "STORAGE", "WARNING", "CAUTION",
    "KEEP", "REACH", "CHILDREN", "DIRECTED", "PHYSICIAN",
    "COMPOSITION", "CONTAINS", "CONTAIN",
    "FILM", "COATED", "UNCOATED", "BILAYERED",
    "EQUIVALENT", "EXCIPIENTS", "COLOUR", "COLOR",
    "IP", "USP", "BP",
    // REMOVED: "MG", "ML", "GM", "MCG", "GRAM" — see FIX 5
    "GRAM",
    "HYDROCHLORIDE", "SODIUM", "CHLORIDE", "ACETATE",
    "COOL", "DRY", "PLACE", "PROTECT", "LIGHT", "MOISTURE",
    "USE", "TAKE", "DAILY", "EACH", "DOSE",
    "BY", "OF", "THE", "AND", "OR", "TO", "IN",
    "AS", "FOR", "IS", "AN", "FROM", "WITH",
    "INDIA", "LTD", "PVT", "LIMITED",
    "PHARMA", "PHARMACEUTICALS", "LABORATORIES", "LABS",
    "MADE", "MANUFACTURED", "MFG", "REGD", "TRADE", "MARK",
    "RX", "TION", "ABLE", "ANALGESIC", "ANALGESICS", "ANTIPYRETIC",
    "BELOW",
    "COLOURS", "COLORS",
    "DOSING",
    "EVERY", "EXCEEDING",
    "FERRIC",
    "LAKE",
    "OXIDE", "PONCEAU",
    "SUSTAINED",
    "TAKING", "TEMPERATURE", "TITANIUM",
    "INTERVAL",
    "APEX", "SERDIA", "WALUJ", "MUMBAI", "DELHI", "CHENNAI",
    "STONE", "CUTTER"
)

// Category-signal tokens: not brand tokens, but used to confirm/boost category
// scoring when present. Kept separate from STOPWORDS so they aren't stripped.
private val CATEGORY_TOKENS = setOf(
    "INJECTION", "INJ", "VIAL", "AMPOULE", "AMPULE",
    "SYRUP", "SUSPENSION", "ELIXIR", "LINCTUS",
    "CREAM", "OINTMENT", "GEL", "LOTION", "EMULSION",
    "DROPS", "EYE", "EAR", "NASAL",
    "POWDER", "SACHET",
    "TONIC", "LIQUID",
)

val CHAR_FIXES = mapOf(
    '$' to 'S', '@' to 'A', '!' to 'I', '|' to 'I',
    '0' to 'O', '1' to 'I', '2' to 'Z', '3' to 'E',
    '4' to 'A', '5' to 'S', '6' to 'G', '7' to 'T',
    '8' to 'B', '9' to 'G'
)

// ===========================================================================
// PrecomputedMedicine
// ===========================================================================
private data class PrecomputedMedicine(
    val medicine: Medicine,
    val medNameRaw: String,
    val brandKey: String,
    val medTokens: List<String>,
    val medChars: String,
    val medNumbers: Set<String>,
    val medBigrams: Set<String>,
    val aliasGenerics: List<String>,
    val medDosageSuffixes: Set<String>,   // precomputed — was recomputed every call
    val medCategoryTokens: Set<String>,   // precomputed category signals
)

// ===========================================================================
// TemporalVoteBuffer — FIX 7
// Rolling 5-frame window. A result is "confirmed" only when the same top-1
// medicine name appears in 3 of the last 5 frames, OR when the same medicine
// holds the top score for 4 consecutive frames (fast-lock for clear packages).
// This eliminates flicker from single bad OCR frames.
// ===========================================================================
class TemporalVoteBuffer(private val windowSize: Int = 5, private val minVotes: Int = 3) {
    private val window = ArrayDeque<String>(windowSize)   // medicine names, "" = no match

    /** Feed the top-1 result of the latest frame. Returns confirmed name or null. */
    fun feed(topMedicineName: String?): String? {
        if (window.size >= windowSize) window.removeFirst()
        window.addLast(topMedicineName ?: "")

        if (window.size < minVotes) return null

        // Count votes
        val freq = window.groupingBy { it }.eachCount()
        val best = freq.maxByOrNull { it.value } ?: return null
        if (best.key.isEmpty()) return null

        // Confirmed if vote threshold met
        if (best.value >= minVotes) return best.key

        // Fast-lock: same name in last 4 consecutive frames
        if (window.size >= 4 && window.toList().takeLast(4).all { it == window.last() } && window.last().isNotEmpty()) {
            return window.last()
        }
        return null
    }

    fun reset() = window.clear()
}

object Matcher {

    data class ScoredMatch(
        val medicine: Medicine,
        val score: Double,
        val explanation: String
    )

    private var precomputedDb: List<PrecomputedMedicine> = emptyList()

    private val REVERSE_ALIAS: Map<String, Set<String>> by lazy {
        val map = mutableMapOf<String, MutableSet<String>>()
        for ((brandKey, generics) in ALIASES) {
            for (generic in generics) {
                map.getOrPut(generic.uppercase(Locale.ROOT)) { mutableSetOf() }.add(brandKey)
            }
        }
        map
    }

    fun buildIndex() {
        val db = MedicineRepository.getDatabase()
        precomputedDb = db.map { med ->
            val raw    = med.name.uppercase(Locale.ROOT)
            val chars  = raw.replace(Regex("[^A-Z0-9]"), "")
            val key    = extractBrandKey(raw)
            val tokens = tokenize(raw)
            PrecomputedMedicine(
                medicine           = med,
                medNameRaw         = raw,
                brandKey           = key,
                medTokens          = tokens,
                medChars           = chars,
                medNumbers         = extractNumbers(raw),
                medBigrams         = bigrams(chars),
                aliasGenerics      = ALIASES[key] ?: emptyList(),
                medDosageSuffixes  = extractDosageSuffixes(raw),   // FIX 5: precomputed
                medCategoryTokens  = raw.split(Regex("\\s+"))
                    .filter { it in CATEGORY_TOKENS }.toSet(),
            )
        }
        Log.d("MATCHER", "Index built: ${precomputedDb.size} medicines precomputed")
    }

    // =======================================================================
    // MAIN ENTRY POINT
    // =======================================================================
    fun findTopMatches(
        rawInput: String,
        blacklist: Set<String>,
        maxResults: Int = 3,
        categoryFilter: ProductCategory? = null
    ): List<ScoredMatch> {

        if (precomputedDb.isEmpty()) buildIndex()

        val trimmedInput = rawInput.trim().uppercase(Locale.ROOT)
        if (trimmedInput == "NOT FOUND" || trimmedInput == "NOT_FOUND" || trimmedInput == "NONE" || trimmedInput == "UNKNOWN" || trimmedInput.isBlank()) {
            Log.d("MATCHER", "Skipping matching for generic input: $trimmedInput")
            return emptyList()
        }

        Log.d("MATCHER", "========== MATCHING START ==========")

        // 1. O(1) HashMap Exact Match check
        val exactQuery = normalize(rawInput)
        val exactMed = MedicineRepository.getMedicineByName(exactQuery)
        if (exactMed != null && exactMed.name !in blacklist) {
            Log.d("MATCHER", "O(1) HashMap Exact Match Found: ${exactMed.name}")
            return listOf(ScoredMatch(exactMed, 100.0, "EXACT_HASHMAP_MATCH"))
        }

        // Clean raw input first to remove branding, split colons, and fix common brand typos
        val cleanedInput = NumericOcrCorrector.cleanOcrText(rawInput)

        // Correct OCR-confused strengths before the name-oriented char fixes;
        // otherwise valid dosage digits like 650 become GSO and lose number scoring.
        val numericCorrectedInput = NumericOcrCorrector.correct(cleanedInput)
        val charFixedInput = numericCorrectedInput.uppercase(Locale.ROOT)
            .map { CHAR_FIXES[it] ?: it }
            .joinToString("")

        val inputNumbers = extractNumbers(numericCorrectedInput)   // Keep dosage numbers as digits.

        val normalizedInput = preprocessForMatch(numericCorrectedInput)  // still handles full pipeline

        // FIX 8: Reject inputs that are pure-digit after preprocessing
        if (normalizedInput.length < 3) return emptyList()
        if (normalizedInput.replace(" ", "").all { it.isDigit() }) {
            Log.d("MATCHER", "Rejected: pure-digit input after preprocessing")
            return emptyList()
        }

        val inputTokens = tokenize(normalizedInput)
        Log.d("MATCHER", "Tokens: $inputTokens")
        if (inputTokens.isEmpty()) return emptyList()

        val inputChars   = normalizedInput.replace(" ", "")
        val inputBigrams = bigrams(inputChars)

        // FIX 5: Extract dosage suffixes from the char-fixed input BEFORE stopword
        // stripping so MG/ML/MCG are still present in the raw string.
        val inputDosageSuffixes = extractDosageSuffixes(numericCorrectedInput)

        val inputAliasKeys = buildInputAliasKeys(inputTokens)
        Log.d("MATCHER", "Alias keys: $inputAliasKeys  Dosage suffixes: $inputDosageSuffixes")

        // FIX 9: Cascading category search.
        // When categoryFilter == null (Other / low confidence), instead of
        // brute-force searching all 8500 medicines, first try to infer a likely
        // category from OCR text signals, then cascade to all if nothing found.
        val database = when {
            categoryFilter != null -> {
                val allowed = MedicineRepository.getByCategoryFilter(categoryFilter).toHashSet()
                precomputedDb.filter { it.medicine in allowed }
            }
            else -> {
                val inferredCategory = inferCategoryFromTokens(charFixedInput)
                if (inferredCategory != null) {
                    Log.d("MATCHER", "Inferred category from OCR tokens: $inferredCategory")
                    val allowed = MedicineRepository.getByCategoryFilter(inferredCategory).toHashSet()
                    val narrowResults = precomputedDb
                        .filter { it.medicine in allowed && it.medicine.name !in blacklist }
                    // If narrow search gives confident results, use them.
                    // Otherwise fall through to full DB.
                    if (narrowResults.size >= 5) narrowResults else precomputedDb
                } else {
                    precomputedDb
                }
            }
        }

        val candidateLimit = 300

        data class BigramCandidate(val precomp: PrecomputedMedicine, val bigramScore: Double)

        val candidates = database
            .asSequence()
            .filter { it.medicine.name !in blacklist }
            .map { precomp ->
                val aliasForced = precomp.brandKey in inputAliasKeys
                val bScore = if (aliasForced) 100.0
                else bigramJaccard(inputBigrams, precomp.medBigrams)
                BigramCandidate(precomp, bScore)
            }
            .filter { it.bigramScore >= 3.0 }
            .sortedByDescending { it.bigramScore }
            .take(candidateLimit)
            .toList()

        Log.d("MATCHER", "Phase 1: ${candidates.size} candidates")

        val results = candidates
            .map { candidate ->
                scoreMedicine(
                    precomp             = candidate.precomp,
                    inputTokens         = inputTokens,
                    inputAliasKeys      = inputAliasKeys,
                    inputChars          = inputChars,
                    inputNumbers        = inputNumbers,
                    inputDosageSuffixes = inputDosageSuffixes,
                )
            }
            .filter { it.score >= 55.0 }
            .sortedByDescending { it.score }
            .distinctBy { it.medicine.name }
            .take(maxResults)

        results.forEachIndexed { i, m ->
            Log.d("MATCHER", "#${i+1}: ${m.medicine.name} -> ${m.score.toInt()}% [${m.explanation}]")
        }

        return results
    }

    fun normalize(text: String): String {
        val cleaned = NumericOcrCorrector.cleanOcrText(text)
        val clean = NumericOcrCorrector.correct(cleaned)
            .replace(Regex("[^A-Za-z0-9 \\-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val fixed = buildString {
            for (ch in clean) append(CHAR_FIXES[ch] ?: ch)
        }
        return NumericOcrCorrector.correct(fixed).uppercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun preprocessForMatch(rawInput: String): String = preprocessInput(rawInput)

    fun debugInput(raw: String): String {
        val cleaned = NumericOcrCorrector.cleanOcrText(raw)
        val numericCorrected = NumericOcrCorrector.correct(cleaned)
        val preprocessed = preprocessInput(numericCorrected)
        val numbers = extractNumbers(numericCorrected)
        return "NUMERIC='$numericCorrected' PREPROCESS='$preprocessed' NUMBERS=$numbers | DISPLAY='${normalize(raw)}'"
    }

    // =======================================================================
    // SCORING
    // FIX 6: Rescaled score bands so the total across all components can
    // meaningfully exceed 55 (the filter floor) without being capped at 100
    // by ALIAS_GATE + NUM_MATCH alone.
    // New band allocation:
    //   Alias gate:      +30  (was +40 — reduced so other signals still matter)
    //   Token exact:      +6 to +18 per token (unchanged lengthBoost)
    //   Token fuzzy:      up to +12 per token (unchanged)
    //   Alias token:      +12 (unchanged)
    //   Number exact:     +30  (was +35)
    //   Number fuzzy:     +20  (was +25)
    //   Number partial:   +12  (was +15)
    //   Number mismatch:  -20  (was -25)
    //   Number orphan:    -8   (was -10)
    //   Dosage suffix:    +10 per shared suffix (was +12)
    //   Brand prefix:     +15 (unchanged)
    //   Bigram:           ×0.5 (was ×0.6)
    //   Subsequence:      ×0.12 (was ×0.15)
    //   Category confirm: +8  (new — reward when category token matches)
    // =======================================================================
    private fun scoreMedicine(
        precomp: PrecomputedMedicine,
        inputTokens: List<String>,
        inputAliasKeys: Set<String>,
        inputChars: String,
        inputNumbers: Set<String>,
        inputDosageSuffixes: Set<String>,
    ): ScoredMatch {

        val reasons = mutableListOf<String>()
        var score   = 0.0

        val medTokens        = precomp.medTokens
        val medNumbers       = precomp.medNumbers
        val medChars         = precomp.medChars
        val aliasGenerics    = precomp.aliasGenerics
        val brandKey         = precomp.brandKey

        // Gate
        val aliasGatePasses = brandKey in inputAliasKeys
        if (!aliasGatePasses) {
            val bestTokenSim = inputTokens.maxOfOrNull { input ->
                val directBest = medTokens.maxOfOrNull { medTok ->
                    if (abs(input.length - medTok.length) > maxOf(input.length, medTok.length) * 0.4) 0.0
                    else similarity(input, medTok)
                } ?: 0.0
                val aliasBest = aliasGenerics.maxOfOrNull { g ->
                    similarity(input, g.uppercase(Locale.ROOT))
                } ?: 0.0
                max(directBest, aliasBest)
            } ?: 0.0

            if (bestTokenSim < 0.65) {
                return ScoredMatch(precomp.medicine, 0.0, "GATE_FAIL(sim=${(bestTokenSim*100).toInt()}%)")
            }
        }

        if (aliasGatePasses) {
            score += 30.0   // FIX 6: was 40
            reasons.add("ALIAS_GATE($brandKey)")
        }

        // 1. Token score
        var tokenScore = 0.0
        for (input in inputTokens) {
            // Skip category tokens in brand scoring (they are scored separately below)
            if (input in CATEGORY_TOKENS) continue

            for (medToken in medTokens) {
                if (abs(input.length - medToken.length) > maxOf(input.length, medToken.length) * 0.4) continue
                val sim = similarity(input, medToken)
                if (input == medToken) {
                    val boost = lengthBoost(medToken)
                    tokenScore += boost
                    reasons.add("EXACT($input,+$boost)")
                    continue
                }
                if (input.length >= 5 && medToken.length >= 5 && sim >= 0.80) {
                    val boost = (sim * lengthBoost(medToken)).coerceAtMost(12.0)
                    tokenScore += boost
                    reasons.add("FUZZY($input~$medToken,${(sim*100).toInt()}%)")
                }
            }
            val aliasMatchSim = aliasGenerics.maxOfOrNull {
                similarity(input, it.uppercase(Locale.ROOT))
            } ?: 0.0
            if (aliasMatchSim >= 0.85) {
                tokenScore += 12.0
                reasons.add("ALIAS_TOKEN($input,${(aliasMatchSim*100).toInt()}%)")
            }
        }
        score += tokenScore

        // 2. Number match
        val commonNumbers = inputNumbers.intersect(medNumbers)
        val partialNumberMatch = inputNumbers.any { inp ->
            medNumbers.any { med -> med.startsWith(inp) || inp.startsWith(med) }
        }
        val fuzzyNumberMatch = inputNumbers.any { inp ->
            medNumbers.any { med -> isLikelyOcrNumberMatch(inp, med) }
        }
        when {
            commonNumbers.isNotEmpty() -> {
                score += 30.0   // FIX 6: was 35
                reasons.add("NUM_MATCH($commonNumbers)")
            }
            fuzzyNumberMatch -> {
                score += 20.0   // FIX 6: was 25
                reasons.add("NUM_FUZZY_MATCH(in=$inputNumbers,med=$medNumbers)")
            }
            partialNumberMatch -> {
                score += 12.0   // FIX 6: was 15
                reasons.add("NUM_PARTIAL_MATCH")
            }
            inputNumbers.isNotEmpty() && medNumbers.isNotEmpty() -> {
                score -= 20.0   // FIX 6: was -25
                reasons.add("NUM_MISMATCH(in=$inputNumbers,med=$medNumbers)")
            }
            inputNumbers.isNotEmpty() -> {
                score -= 8.0    // FIX 6: was -10
                reasons.add("NUM_ORPHAN")
            }
        }

        // 3. Dosage suffix — FIX 5: now uses precomputed medDosageSuffixes
        // and inputDosageSuffixes extracted BEFORE stopword stripping
        val commonSuffixes = inputDosageSuffixes.intersect(precomp.medDosageSuffixes)
        if (commonSuffixes.isNotEmpty()) {
            score += commonSuffixes.size * 10.0   // FIX 6: was 12
            reasons.add("DOSAGE_SUFFIX:$commonSuffixes")
            Log.d("DOSAGE_SUFFIX", "Matched: $commonSuffixes")
        }

        // 4. Category token confirmation — FIX 4 (new scoring)
        // When OCR sees "INJECTION" and the medicine IS an injection, award a bonus.
        val inputCategoryTokens = inputTokens.filter { it in CATEGORY_TOKENS }.toSet()
        val commonCategoryTokens = inputCategoryTokens.intersect(precomp.medCategoryTokens)
        if (commonCategoryTokens.isNotEmpty()) {
            score += commonCategoryTokens.size * 8.0
            reasons.add("CATEGORY_CONFIRM:$commonCategoryTokens")
        }

        // 5. Brand prefix bonus
        for (token in inputTokens) {
            if (token in CATEGORY_TOKENS) continue
            if (token.length >= 4 &&
                (precomp.medNameRaw.startsWith(token) ||
                 precomp.medNameRaw.replace("-", "").startsWith(token) ||
                 precomp.medNameRaw.replace(Regex("[\\s\\-]"), "").startsWith(token))) {
                score += 15.0
                reasons.add("BRAND_PREFIX($token)")
                break
            }
        }

        // 6. Bigram similarity — FIX 6: weight reduced from 0.6 → 0.5
        val inputBigrams = bigrams(inputChars)
        score += bigramJaccard(inputBigrams, precomp.medBigrams) * 0.5

        // 7. Subsequence score — FIX 6: weight reduced from 0.15 → 0.12
        score += subsequenceScore(inputChars, medChars) * 0.12

        return ScoredMatch(precomp.medicine, score.coerceIn(0.0, 100.0), reasons.joinToString(", "))
    }

    // =======================================================================
    // FIX 9: Infer a ProductCategory from raw OCR text signals.
    // Called only when TFLite returns Other/low-confidence.
    // Returns null if no strong signal found → caller uses full DB.
    // =======================================================================
    private fun inferCategoryFromTokens(charFixedInput: String): ProductCategory? {
        val upper = charFixedInput.uppercase(Locale.ROOT)
        return when {
            Regex("\\b(INJ|INJECTION|VIAL|AMPOULE|AMPULE|IV|IM)\\b").containsMatchIn(upper)       -> ProductCategory.INJECTION
            Regex("\\b(SACHET|PDR|POWDER|RECONSTITUT)\\b").containsMatchIn(upper)                  -> ProductCategory.POWDER
            Regex("\\b(SYRUP|SUSPENSION|ELIXIR|LINCTUS|TONIC|ORAL\\s+LIQUID)\\b").containsMatchIn(upper) -> ProductCategory.TONIC
            Regex("\\b(CREAM|OINTMENT|OINT|GEL|PASTE)\\b").containsMatchIn(upper)                  -> ProductCategory.CREAM
            Regex("\\b(DROPS|EYE\\s+DROP|EAR\\s+DROP|NASAL\\s+DROP|ORAL\\s+DROP)\\b").containsMatchIn(upper) -> ProductCategory.DROPS
            Regex("\\b(LOTION|EMULSION|SOLUTION\\s+FOR\\s+SKIN)\\b").containsMatchIn(upper)        -> ProductCategory.LOTION
            Regex("\\b(TABLET|CAPSULE|CAP\\b|TAB\\b|CAPLET|CAPLETS|SOFTGEL)\\b").containsMatchIn(upper) -> ProductCategory.PILL
            else -> null
        }
    }

    private fun buildInputAliasKeys(inputTokens: List<String>): Set<String> {
        val keys = mutableSetOf<String>()
        for (token in inputTokens) {
            REVERSE_ALIAS[token]?.let { keys.addAll(it) }
            if (token.length >= 6) {
                for ((generic, brandKeys) in REVERSE_ALIAS) {
                    if (generic.length >= 6 &&
                        abs(token.length - generic.length) <= 3 &&
                        similarity(token, generic) >= 0.85
                    ) {
                        keys.addAll(brandKeys)
                    }
                }
            }
        }
        return keys
    }

    // =======================================================================
    // HELPERS
    // =======================================================================

    internal fun extractBrandKey(medNameRaw: String): String {
        val parts = medNameRaw.split(Regex("[\\s\\-/]+")).filter { it.isNotEmpty() }
        if (parts.isNotEmpty()) {
            val first = parts[0].uppercase(Locale.ROOT)
            if (first.length < 3 && parts.size > 1) {
                val second = parts[1].uppercase(Locale.ROOT)
                if (second.all { it.isLetter() }) {
                    val fused = first + second
                    if (fused.length >= 4 && fused.all { it.isLetter() }) {
                        return fused
                    }
                }
            }
            parts.forEach { part ->
                val upper = part.uppercase(Locale.ROOT)
                if (upper.length >= 4 && upper.all { it.isLetter() }) {
                    return upper
                }
            }
        }
        return medNameRaw.replace(Regex("[^A-Z]"), "").take(8)
    }

    private fun lengthBoost(token: String): Double = when {
        token.length >= 10 -> 18.0
        token.length >= 8  -> 14.0
        token.length >= 6  -> 10.0
        token.length >= 4  -> 6.0
        else               -> 2.0
    }

    private fun preprocessInput(rawInput: String): String {
        // Step 1: Clean branding and colon-between-brand-strength
        val cleaned = NumericOcrCorrector.cleanOcrText(rawInput)

        // Step 2: Split letter-digit and digit-letter boundaries
        val text = cleaned.uppercase(Locale.ROOT)
            .replace(Regex("""([A-Z])(\d)"""), "$1 $2")
            .replace(Regex("""(\d)([A-Z])"""), "$1 $2")

        // Step 3: Split on spaces, handle hyphens with fusion, apply fixes
        val spaceSplit = text.split(Regex("\\s+"))

        // For each space-token, split on hyphens and also yield the no-hyphen concat
        val splitHyphens = spaceSplit.flatMap { token ->
            if (token.contains("-")) {
                val parts    = token.split("-").filter { it.isNotEmpty() }
                val noHyphen = token.replace("-", "")
                if (noHyphen.isNotEmpty()) parts + noHyphen else parts
            } else {
                listOf(token)
            }
        }

        // Fuse short prefix tokens BEFORE the length filter
        val fused = fuseShortTokens(splitHyphens)

        val tokens = fused
            .map { token ->
                val clean = token.replace(Regex("[^A-Z0-9]"), "")
                val charFixed = if (clean.all { it.isDigit() } || clean.isEmpty()) {
                    clean
                } else {
                    clean.map { CHAR_FIXES[it] ?: it }.joinToString("")
                }
                OCR_WORD_CORRECTIONS[charFixed] ?: charFixed
            }
            .filter { token ->
                token.length >= 3 &&
                        !token.all { it.isDigit() } &&
                        token !in STOPWORDS &&
                        !isGibberish(token)
            }

        return tokens.distinct().joinToString(" ")
    }

    private fun extractNumbers(text: String): Set<String> =
        Regex("\\d+").findAll(text).map { it.value }.toSet()

    private fun isLikelyOcrNumberMatch(input: String, medicine: String): Boolean {
        if (input.length != medicine.length || input.length !in 2..4) return false
        return levenshtein(input, medicine) == 1
    }

    internal fun tokenize(text: String): List<String> {
        val rawUpper = text.uppercase(Locale.ROOT)

        // Split on spaces/slashes first (keep hyphens temporarily)
        val spaceSplit = rawUpper
            .replace(Regex("[^A-Z0-9 \\-]"), "")
            .split(Regex("[\\s/]+"))
            .filter { it.isNotEmpty() }

        // For hyphenated tokens: keep parts AND no-hyphen concat
        val splitHyphens = spaceSplit.flatMap { token ->
            if (token.contains("-")) {
                val parts    = token.split("-").filter { it.isNotEmpty() }
                val noHyphen = token.replace("-", "")
                if (noHyphen.isNotEmpty()) parts + noHyphen else parts
            } else {
                listOf(token)
            }
        }

        // Fuse short prefix tokens before length filter
        val fused = fuseShortTokens(splitHyphens)

        return fused
            .map { it.trim().trimStart(')', '(', '-', '.', ',', '*', '}', '{') }
            .filter { token ->
                token.length >= 3 &&
                        token !in STOPWORDS &&
                        !token.all { it.isDigit() }
            }
    }

    // FIX 5: extractDosageSuffixes now operates on the raw (char-fixed) text
    // string, not on tokens after stopword stripping.
    // Also added GM (grams), SUSP, OZ as real dosage signals.
    private fun extractDosageSuffixes(text: String): Set<String> {
        val suffixes = setOf(
            "MG", "ML", "MCG", "GM",       // units — now preserved (FIX 5)
            "SR", "ER", "CR", "XL", "OD",  // release/frequency forms
            "TAB", "CAP",
            "DT", "DS",
            "FORTE", "PLUS",
            "IV", "IM",
            "SUSP", "OZ",
        )
        return text
            .uppercase()
            .split(Regex("[^A-Z0-9]+"))
            .filter { it in suffixes }
            .toSet()
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val lenDiff = abs(a.length - b.length)
        if (lenDiff > maxOf(a.length, b.length) * 0.5) return lenDiff
        val band = (maxOf(a.length, b.length) * 0.35).toInt().coerceAtLeast(2)
        val dp   = Array(a.length + 1) { IntArray(b.length + 1) { Int.MAX_VALUE / 2 } }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            val jStart = maxOf(1, i - band)
            val jEnd   = minOf(b.length, i + band)
            for (j in jStart..jEnd) {
                val cost = if (a[i-1] == b[j-1]) 0 else 1
                dp[i][j] = minOf(dp[i-1][j] + 1, dp[i][j-1] + 1, dp[i-1][j-1] + cost)
            }
        }
        return dp[a.length][b.length]
    }

    private fun similarity(a: String, b: String): Double {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun bigramJaccard(aGrams: Set<String>, bGrams: Set<String>): Double {
        if (aGrams.isEmpty() || bGrams.isEmpty()) return 0.0
        val intersection = aGrams.intersect(bGrams).size
        if (intersection == 0) return 0.0
        val union = (aGrams.size + bGrams.size - intersection).coerceAtLeast(1)
        return (intersection.toDouble() / union) * 100
    }

    private fun bigrams(text: String): Set<String> =
        if (text.length < 2) emptySet()
        else (0 until text.length - 1).mapTo(HashSet()) { text.substring(it, it + 2) }

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
