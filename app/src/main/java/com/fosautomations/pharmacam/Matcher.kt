package com.fosautomations.pharmacam

import android.util.Log
import java.util.*
import kotlin.math.*

// ---------------------------------------------------------------------------
// ALIASES — generic ingredient names that map to a brand
// Key   = first pure-alpha token of the DB medicine name (≥4 chars, uppercase)
// Value = list of generic / OCR-variant names for that brand
// ---------------------------------------------------------------------------
private val ALIASES: Map<String, List<String>> = mapOf(
    "DOLO"       to listOf("PARACETAMOL", "PAROCETAMOL", "PUROCETAMOL", "PUROCETOMOL", "FUROCETOMOL", "FUROCETAMOL", "ACETAMINOPHEN", "CROCIN", "CALPOL", "PACIMOL", "DOLO-650", "DOLO650", "DOLE650"),
    "DOLOPAR"    to listOf("PARACETAMOL", "DOLO-650", "DOLO650"),
    "CROCIN"     to listOf("PARACETAMOL", "ACETAMINOPHEN"),
    "CALPOL"     to listOf("PARACETAMOL", "ACETAMINOPHEN"),
    "AZTOR" to listOf("ATORVASTATIN", "ATORVASTATIN CALCIUM", "LIPITOR", "AZTOR20"),
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
    "ALKALIZER"  to listOf("ALKAZAR"),
    "ALKOF"      to listOf("COFGELS", "COFGEL"),
)

private val OCR_WORD_CORRECTIONS = mapOf(
    // Tablet corruptions
    "TOBLET" to "TABLET", "TOBLETS" to "TABLET", "TOHLET" to "TABLET",
    "TOHLETS" to "TABLET", "TABLST" to "TABLET", "TABIET" to "TABLET",
    "TABTET" to "TABLET", "TABLEL" to "TABLET", "TABT" to "TABLET",
    "TABLEIS" to "TABLET", "TAOLETS" to "TABLET", "TUBLET" to "TABLET",
    "TAHLOT" to "TABLET", "TATIET" to "TABLET", "TATLET" to "TABLET",
    "TABLAT" to "TABLET", "TATLELS" to "TABLET", "ABLET" to "TABLET",
    "ABIET" to "TABLET", "ABLST" to "TABLET", "LOBLETS" to "TABLETS",
    "YABLETS" to "TABLETS", "TAHLETS" to "TABLETS", "TEBLET" to "TABLET",
    "TEBLETS" to "TABLETS",

    // Uncoated corruptions
    "UNCOETED" to "UNCOATED", "UNCOATOD" to "UNCOATED", "UNCOABED" to "UNCOATED",
    "UNCOTD" to "UNCOATED", "UNCOTED" to "UNCOATED", "UNCOATD" to "UNCOATED",
    "UNCCATOD" to "UNCOATED", "UNCOABAD" to "UNCOATED", "UNCOSTED" to "UNCOATED",
    // Contains corruptions
    "CONTALNS" to "CONTAINS", "CONTALIS" to "CONTAINS", "CONTSLIS" to "CONTAINS",
    "CONTANS" to "CONTAINS", "CONTAIS" to "CONTAINS", "OONTALNS" to "CONTAINS",
    "OONTAINS" to "CONTAINS", "OONTALNA" to "CONTAINS", "COSTALNS" to "CONTAINS",
    "CANTAINS" to "CONTAINS", "CUNTEINS" to "CONTAINS", "COTINS" to "CONTAINS",
    "COALNS" to "CONTAINS", "RONTAINS" to "CONTAINS", "ONTEINS" to "CONTAINS",
    "OORTANS" to "CONTAINS", "ORTALNS" to "CONTAINS", "CORTALNS" to "CONTAINS",
    // Storage/Store corruptions
    "STORIG" to "STORAGE", "STORS" to "STORE", "STOR" to "STORE",
    // Directed/Physician corruptions
    "DIRECTOD" to "DIRECTED", "DIRSCTED" to "DIRECTED", "DIRCCTCD" to "DIRECTED",
    "DIRCTEC" to "DIRECTED", "DIRECTC" to "DIRECTED", "DRECTED" to "DIRECTED",
    "PHRGSCAN" to "PHYSICIAN", "PRYSCIAN" to "PHYSICIAN", "PHYSCIAN" to "PHYSICIAN",
    "PHYSTCIAN" to "PHYSICIAN",
    // Keep/Children corruptions
    "KEOP" to "KEEP", "KEAP" to "KEEP", "KOEP" to "KEEP",
    "CHLDREN" to "CHILDREN", "CHLLDREN" to "CHILDREN", "CHLOREN" to "CHILDREN",
    "CHLDRAN" to "CHILDREN", "CHLLDRAN" to "CHILDREN",
    // Protect/Light/Moisture corruptions
    "PROTEOT" to "PROTECT", "PROT" to "PROTECT",
    "LLGHT" to "LIGHT", "LGHT" to "LIGHT",
    "MOLSTURE" to "MOISTURE", "NOLSTURE" to "MOISTURE", "MOLSTUE" to "MOISTURE",
    "MOSTURE" to "MOISTURE", "NOLSTUE" to "MOISTURE",
    // Dosage corruptions
    "DOSAGA" to "DOSAGE", "DOSAGS0" to "DOSAGE", "DOSAC" to "DOSAGE",
    "DOSSGE" to "DOSAGE", "DOSAYE" to "DOSAGE",
    // Medicine corruptions
    "MEDLCINE" to "MEDICINE", "MODICINE" to "MEDICINE",
    // Registered corruptions
    "BEGISTEED" to "REGISTERED", "REGISTSRO" to "REGISTERED",
    "BEGISLERD" to "REGISTERED", "OBGISTARD" to "REGISTERED",
    "REGISTERE" to "REGISTERED", "REGSTERED" to "REGISTERED",
    // Analgesic
    "ANALGESC" to "ANALGESIC", "ANALGESLC" to "ANALGESIC",
    // Place/Below
    "PLCE" to "PLACE", "PLICO" to "PLACE", "PLICE" to "PLACE",
    "BALOW" to "BELOW", "BEOW" to "BELOW",
    "DELOO" to "DOLO", "DELO" to "DOLO", "DOLO0" to "DOLO",
    "POLOGS" to "DOLO", "POLO" to "DOLO", "DOLE" to "DOLO",
    "DOLS" to "DOLO", "DOLG" to "DOLO", "DOLB" to "DOLO",
    "DYLO" to "DOLO",
)

private val VOWELS = setOf('A', 'E', 'I', 'O', 'U')

private fun isGibberish(token: String): Boolean {
    if (token.length < 4) return true
    val vowelCount = token.count { it in VOWELS }
    val vowelRatio = vowelCount.toDouble() / token.length
    // Real words have at least 15% vowels
    // "TBLTS", "MFGD", "RQZX" etc. are gibberish
    return vowelRatio < 0.15
}

// ---------------------------------------------------------------------------
// STOPWORDS — tokens with zero brand-identity signal
// ---------------------------------------------------------------------------
private val STOPWORDS = setOf(
    "TABLET", "TABLETS", "TAB", "TABS",
    "CAP", "CAPS", "CAPSULE", "CAPSULES",
    "SYRUP", "SUSPENSION", "INJECTION", "INJ",
    "TABLETSIP", "TABLETIP",
    "RELEASE", "PRESCRIPTION", "REGISTERED",
    "DOSAGE", "STORE", "STORAGE", "WARNING", "CAUTION",
    "KEEP", "REACH", "CHILDREN", "DIRECTED", "PHYSICIAN",
    "COMPOSITION", "CONTAINS", "CONTAIN",
    "FILM", "COATED", "UNCOATED", "BILAYERED",
    "EQUIVALENT", "EXCIPIENTS", "COLOUR", "COLOR",
    "IP", "USP", "BP",
    "MG", "ML", "GM", "MCG", "GRAM",
    "HYDROCHLORIDE", "SODIUM", "CHLORIDE", "ACETATE",
    "COOL", "DRY", "PLACE", "PROTECT", "LIGHT", "MOISTURE",
    "USE", "TAKE", "DAILY", "EACH", "DOSE",
    "BY", "OF", "THE", "AND", "OR", "TO", "IN",
    "AS", "FOR", "IS", "AN", "FROM", "WITH",
    "INDIA", "LTD", "PVT", "LIMITED",
    "PHARMA", "PHARMACEUTICALS", "LABORATORIES", "LABS",
    "MADE", "MANUFACTURED", "MFG", "REGD", "TRADE", "MARK",
    "RX", "TION", "ABLE", "ANALGESIC", "ANALGESICS", "ANTIPYRETIC",
    "BELOW", "COATED", "UNCOATED",
    "COLOUR", "COLOR", "COLOURS", "COLORS",
    "CONTAIN", "CONTAINS",
    "DOSAGE", "DOSE", "DOSING",
    "EACH", "EVERY", "EXCEEDING",
    "EXCIPIENTS", "FERRIC", "FILM", "BILAYERED",
    "KEEP", "LAKE", "LIGHT", "MEDICINE", "MOISTURE",
    "OXIDE", "PHYSICIAN", "PLACE", "PONCEAU",
    "PROTECT", "REACH", "REGISTERED", "RELEASE",
    "SODIUM", "STORAGE", "STORE", "SUSTAINED",
    "TAKE", "TAKING", "TEMPERATURE", "TITANIUM",
    "TRADE", "WARNING", "INTERVAL", "DAILY",
    "APEX", "SERDIA", "WALUJ", "MUMBAI", "DELHI", "CHENNAI"
)

val CHAR_FIXES = mapOf(
    '$' to 'S', '@' to 'A', '!' to 'I', '|' to 'I',
    '0' to 'O', '1' to 'I', '2' to 'Z', '3' to 'E',
    '4' to 'A', '5' to 'S', '6' to 'G', '7' to 'T',
    '8' to 'B', '9' to 'G'
)
// ===========================================================================
// PrecomputedMedicine — all expensive fields computed ONCE at startup
// instead of re-computing on every scan for every medicine
// ===========================================================================
private data class PrecomputedMedicine(
    val medicine: Medicine,
    val medNameRaw: String,       // uppercase name
    val brandKey: String,         // first pure-alpha token ≥4 chars
    val medTokens: List<String>,  // tokenized, stopwords removed
    val medChars: String,         // letters+digits only, no spaces
    val medNumbers: Set<String>,  // digit sequences extracted
    val medBigrams: Set<String>,  // character bigrams of medChars (for fast filter)
    val aliasGenerics: List<String> // generic names from ALIASES[brandKey]
)

object Matcher {

    data class ScoredMatch(
        val medicine: Medicine,
        val score: Double,
        val explanation: String
    )

    // -----------------------------------------------------------------------
    // OPTIMIZATION 1: Precomputed index — built ONCE when the DB loads,
    // not rebuilt on every scan call.
    // Also builds the reverse alias map once.
    // -----------------------------------------------------------------------
    private var precomputedDb: List<PrecomputedMedicine> = emptyList()

    // OPTIMIZATION 2: Reverse alias map built once at init
    // Maps generic name → set of brand keys that list it as an alias
    // e.g. "MONTELUKAST" → {"LEVOKAST", "MONTAIR", "MONTEK", ...}
    private val REVERSE_ALIAS: Map<String, Set<String>> by lazy {
        val map = mutableMapOf<String, MutableSet<String>>()
        for ((brandKey, generics) in ALIASES) {
            for (generic in generics) {
                map.getOrPut(generic.uppercase(Locale.ROOT)) { mutableSetOf() }.add(brandKey)
            }
        }
        map
    }

    // Call this once after MedicineRepository loads — e.g. in Application.onCreate()
    // or lazily on first findTopMatches call
    fun buildIndex() {
        val db = MedicineRepository.getDatabase()
        precomputedDb = db.map { med ->
            val raw    = med.name.uppercase(Locale.ROOT)
            val chars  = raw.replace(Regex("[^A-Z0-9]"), "")
            val key    = extractBrandKey(raw)
            val tokens = tokenize(raw)
            PrecomputedMedicine(
                medicine      = med,
                medNameRaw    = raw,
                brandKey      = key,
                medTokens     = tokens,
                medChars      = chars,
                medNumbers    = extractNumbers(raw),
                medBigrams    = bigrams(chars),   // ← precomputed once!
                aliasGenerics = ALIASES[key] ?: emptyList()
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

        // Lazy index build (first call only — ~50ms one-time cost)
        if (precomputedDb.isEmpty()) buildIndex()

        Log.d("MATCHER", "========== MATCHING START ==========")

        val database = categoryFilter?.let { filter ->
            val allowed = MedicineRepository.getByCategoryFilter(filter).toHashSet()
            precomputedDb.filter { it.medicine in allowed }
        } ?: precomputedDb

        val inputNumbers = extractNumbers(rawInput)
        val normalizedInput = preprocessInput(rawInput)

        if (normalizedInput.length < 3) return emptyList()

        val inputTokens = tokenize(normalizedInput)
        Log.d("MATCHER", "Tokens: $inputTokens")
        if (inputTokens.isEmpty()) return emptyList()

        val inputChars  = normalizedInput.replace(" ", "")
        val inputBigrams = bigrams(inputChars)

        // OPTIMIZATION 3: Reverse alias lookup is now O(n_tokens × avg_aliases)
        // using exact map lookup — no Levenshtein at all
        val inputAliasKeys = buildInputAliasKeys(inputTokens)
        Log.d("MATCHER", "Alias keys from input: $inputAliasKeys")

        // ===================================================================
        // PHASE 1 — Bigram pre-filter (NO Levenshtein, pure set intersection)
        //
        // Score every medicine with a fast Jaccard bigram similarity.
        // Keep only the top CANDIDATE_LIMIT medicines for full scoring.
        //
        // WHY THIS WORKS:
        //   - Bigram Jaccard is O(|set|) with precomputed sets — very fast
        //   - Medicines with 0 shared bigrams with the input CANNOT match
        //   - We keep a generous top-K (300) to avoid missing any real match
        //   - This reduces Phase 2 work from 8500 → ~300 medicines
        // ===================================================================
        val candidateLimit  = 300

        data class BigramCandidate(val precomp: PrecomputedMedicine, val bigramScore: Double)

        val candidates = database
            .asSequence()
            .filter { it.medicine.name !in blacklist }
            .map { precomp ->
                // Also force-include any medicine whose brandKey is in inputAliasKeys
                // so alias-matched medicines always reach Phase 2 even if bigrams differ
                val aliasForced = precomp.brandKey in inputAliasKeys
                val bScore = if (aliasForced) {
                    100.0  // guaranteed to pass filter
                } else {
                    // O(1) set operations on precomputed bigram sets
                    bigramJaccard(inputBigrams, precomp.medBigrams)
                }
                BigramCandidate(precomp, bScore)
            }
            // OPTIMIZATION 4: early-exit — anything below 3% bigram overlap
            // cannot possibly score ≥55 in Phase 2 (empirically tuned)
            .filter { it.bigramScore >= 3.0 }
            .sortedByDescending { it.bigramScore }
            .take(candidateLimit )
            .toList()

        Log.d("MATCHER", "Phase 1: ${candidates.size} candidates from ${precomputedDb.size} medicines")

        // ===================================================================
        // PHASE 2 — Full Levenshtein scoring on candidates only
        // ===================================================================
        val results = candidates
            .map { candidate ->
                scoreMedicine(
                    precomp       = candidate.precomp,
                    inputTokens   = inputTokens,
                    inputAliasKeys = inputAliasKeys,
                    inputChars    = inputChars,
                    inputNumbers  = inputNumbers
                )
            }
            .filter  { it.score >= 50.0 }
            .sortedByDescending { it.score }
            .distinctBy { it.medicine.name }
            .take(maxResults)

        results.forEachIndexed { i, m ->
            Log.d("MATCHER", "#${i+1}: ${m.medicine.name} -> ${m.score.toInt()}% [${m.explanation}]")
        }

        return results
    }

    /** UI display + same preprocessing used for matching. */
    fun normalize(text: String): String = preprocessInput(text)

    fun debugInput(raw: String): String {
        val preprocessed = preprocessInput(raw)
        val numbers = extractNumbers(raw)
        return "PREPROCESS='$preprocessed' NUMBERS=$numbers"
    }

    // =======================================================================
    // SCORING — only called on ~300 candidates, not all 8500
    // =======================================================================
    private fun scoreMedicine(
        precomp: PrecomputedMedicine,
        inputTokens: List<String>,
        inputAliasKeys: Set<String>,
        inputChars: String,
        inputNumbers: Set<String>
    ): ScoredMatch {

        val reasons = mutableListOf<String>()
        var score   = 0.0

        // Use precomputed fields — no re-tokenizing, no re-extracting
        val medTokens     = precomp.medTokens
        val medNumbers    = precomp.medNumbers
        val medChars      = precomp.medChars
        val aliasGenerics = precomp.aliasGenerics
        val brandKey      = precomp.brandKey

        // ===================================================================
        // GATE — pass if alias reverse-lookup matches OR token sim ≥ 0.65
        // ===================================================================
        val aliasGatePasses = brandKey in inputAliasKeys

        val bestTokenSim: Double
        if (!aliasGatePasses) {
            bestTokenSim = inputTokens.maxOfOrNull { input ->
                val directBest = medTokens.maxOfOrNull { medTok ->
                    // OPTIMIZATION 5: skip Levenshtein when length gap is too large
                    // If |lenA - lenB| > max(lenA,lenB)*0.4, similarity < 0.6 always
                    if (abs(input.length - medTok.length) > maxOf(input.length, medTok.length) * 0.4)
                        0.0
                    else
                        similarity(input, medTok)
                } ?: 0.0
                val aliasBest = aliasGenerics.maxOfOrNull { g ->
                    similarity(input, g.uppercase(Locale.ROOT))
                } ?: 0.0
                max(directBest, aliasBest)
            } ?: 0.0

            if (bestTokenSim < 0.65) {
                return ScoredMatch(
                    precomp.medicine, 0.0,
                    "GATE_FAIL(sim=${(bestTokenSim*100).toInt()}%)"
                )
            }
        }

        // ===================================================================
        // ALIAS GATE BONUS
        // ===================================================================
        if (aliasGatePasses) {
            score += 40.0
            reasons.add("ALIAS_GATE($brandKey)")
        }

        // ===================================================================
        // 1. TOKEN MATCH SCORE
        // ===================================================================
        var tokenScore = 0.0
        for (input in inputTokens) {
            for (medToken in medTokens) {
                // OPTIMIZATION 5 applied in scoring too
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

            // Per-token alias check
            val aliasMatchSim = aliasGenerics.maxOfOrNull {
                similarity(input, it.uppercase(Locale.ROOT))
            } ?: 0.0
            if (aliasMatchSim >= 0.85) {
                tokenScore += 12.0
                reasons.add("ALIAS_TOKEN($input,${(aliasMatchSim*100).toInt()}%)")
            }
        }
        score += tokenScore

        // ===================================================================
        // 2. NUMBER MATCH / MISMATCH
        // ===================================================================
        val commonNumbers = inputNumbers.intersect(medNumbers)
        val partialNumberMatch = inputNumbers.any { inp ->
            medNumbers.any { med -> med.startsWith(inp) || inp.startsWith(med) }
        }
        when {
            commonNumbers.isNotEmpty() -> {
                score += 35.0
                reasons.add("NUM_MATCH($commonNumbers)")
            }
            partialNumberMatch -> {
                score += 15.0
                reasons.add("NUM_PARTIAL_MATCH")
            }
            inputNumbers.isNotEmpty() && medNumbers.isNotEmpty() -> {
                score -= 25.0
                reasons.add("NUM_MISMATCH(in=$inputNumbers,med=$medNumbers)")
            }
            inputNumbers.isNotEmpty() && medNumbers.isEmpty() -> {
                score -= 10.0
                reasons.add("NUM_ORPHAN")
            }
        }

        // =========================
        // DOSAGE SUFFIX MATCHING
        // =========================


        val inputSuffixes =
            extractDosageSuffixes(
                inputTokens.joinToString(" ")
            )

        val medSuffixes =
            extractDosageSuffixes(
                precomp.medNameRaw
            )

        val commonSuffixes =
            inputSuffixes.intersect(medSuffixes)

        if (commonSuffixes.isNotEmpty()) {

            score += commonSuffixes.size * 12

            reasons.add(
                "DOSAGE_SUFFIX:$commonSuffixes"
            )

            Log.d(
                "DOSAGE_SUFFIX",
                "Matched suffixes: $commonSuffixes"
            )
        }

        // ===================================================================
        // 3. BRAND PREFIX BONUS
        // ===================================================================
        for (token in inputTokens) {
            if (token.length >= 4 && precomp.medNameRaw.startsWith(token)) {
                score += 15.0
                reasons.add("BRAND_PREFIX($token)")
                break
            }
        }

        // ===================================================================
        // 4. BIGRAM SIMILARITY (uses precomputed medBigrams — no recompute!)
        // ===================================================================
        val inputBigrams = bigrams(inputChars)  // inputChars is small — fast
        val bigramScore  = bigramJaccard(inputBigrams, precomp.medBigrams) * 0.6
        score += bigramScore

        // ===================================================================
        // 5. SUBSEQUENCE SCORE
        // ===================================================================
        val subseqScore = subsequenceScore(inputChars, medChars) * 0.15
        score += subseqScore

        return ScoredMatch(precomp.medicine, score.coerceIn(0.0, 100.0), reasons.joinToString(", "))
    }

    // =======================================================================
    // OPTIMIZATION 3: Alias reverse lookup uses exact map — O(tokens × aliases)
    // No Levenshtein, just similarity for fuzzy generic name matching
    // =======================================================================
    private fun buildInputAliasKeys(inputTokens: List<String>): Set<String> {
        val keys = mutableSetOf<String>()
        for (token in inputTokens) {
            // Exact lookup first (O(1))
            REVERSE_ALIAS[token]?.let { keys.addAll(it) }

            // Fuzzy lookup only for tokens ≥6 chars (long enough to be a generic name)
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

    private fun extractBrandKey(medNameRaw: String): String =
        medNameRaw.split(Regex("\\s+"))
            .firstOrNull { it.length >= 4 && it.all { c -> c.isLetter() } }
            ?.uppercase(Locale.ROOT)
            ?: medNameRaw.replace(Regex("[^A-Z]"), "").take(8)

    private fun lengthBoost(token: String): Double = when {
        token.length >= 10 -> 18.0
        token.length >= 8  -> 14.0
        token.length >= 6  -> 10.0
        token.length >= 4  -> 6.0
        else               -> 2.0
    }

    private fun preprocessInput(rawInput: String): String {
        return rawInput
            .uppercase(Locale.ROOT)
            .map { CHAR_FIXES[it] ?: it }
            .joinToString("")
            .split(Regex("\\s+"))
            .flatMap { token ->
                // Split on hyphen FIRST before stripping
                // "Dolo-650" → ["DOLO", "650"]
                // "Polo-6S0" → ["POLO", "6S0"] → ["POLO", "SO"] → ["POLO"] + number "6"
                token.split("-")
            }
            .flatMap { token ->
                val clean = token.replace(Regex("[^A-Z0-9]"), "")
                val corrected = OCR_WORD_CORRECTIONS[clean] ?: clean
                // Split at letter/digit boundary
                Regex("(?<=[A-Z])(?=[0-9])|(?<=[0-9])(?=[A-Z])").split(corrected)
                    .filter { it.isNotEmpty() }
            }
            .filter { token ->
                token.length >= 3 &&
                        !token.all { it.isDigit() } &&
                        token !in STOPWORDS &&
                        !isGibberish(token)
            }
            .joinToString(" ")
    }

    private fun extractNumbers(text: String): Set<String> =
        Regex("\\d+").findAll(text).map { it.value }.toSet()

    private fun tokenize(text: String): List<String> =
        text.uppercase(Locale.ROOT).split(" ")
            .map { it.trim().trimStart(')', '(', '-', '.', ',', '*', '}', '{') }  // ← add this
            .filter { token ->
                token.length >= 3 &&   // ← changed from 4 to 3
                        token !in STOPWORDS &&
                        !token.all { it.isDigit() }
            }

    private fun extractDosageSuffixes(
        text: String
    ): Set<String> {

        val suffixes = setOf(
            "MG",
            "ML",
            "MCG",
            "SR",
            "ER",
            "CR",
            "XL",
            "OD",
            "TAB",
            "CAP",
            "DT",
            "DS",
            "FORTE",
            "PLUS",
            "IV",
            "IM"
        )

        return text
            .uppercase()
            .split(Regex("[^A-Z0-9]+"))
            .filter {
                it in suffixes
            }
            .toSet()
    }

    // OPTIMIZATION 5: early-exit Levenshtein
    // If strings differ in length by more than maxDist, return immediately
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val lenDiff = abs(a.length - b.length)
        // If length diff alone makes similarity < 0.6, skip full computation
        if (lenDiff > maxOf(a.length, b.length) * 0.5) return lenDiff

        // Banded DP — only compute cells within `band` of the diagonal
        val band = (maxOf(a.length, b.length) * 0.35).toInt().coerceAtLeast(2)
        val dp   = Array(a.length + 1) { IntArray(b.length + 1) { Int.MAX_VALUE / 2 } }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            val jStart = maxOf(1, i - band)
            val jEnd   = minOf(b.length, i + band)
            for (j in jStart..jEnd) {
                val cost = if (a[i-1] == b[j-1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i-1][j] + 1,
                    dp[i][j-1] + 1,
                    dp[i-1][j-1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }

    private fun similarity(a: String, b: String): Double {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    // Precomputed bigram Jaccard — takes precomputed Set<String> directly
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