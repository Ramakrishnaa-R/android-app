package com.fosautomations.pharmacam

import java.util.Locale

enum class ClassificationSource {
    /** Indexing medicines.json — infer pill from strip counts (10'S) when form word is missing. */
    DATABASE,
    /** OCR / live scan text — rely on explicit form words on the label. */
    OCR
}

enum class ProductCategory(val displayName: String) {
    PILL("Pill"),
    TONIC("Tonic/Syrup"),
    SUSPENSION("Suspension"),
    DROPS("Drops"),
    LOTION("Lotion"),
    CREAM("Cream"),
    GEL("Gel"),
    OINTMENT("Ointment"),
    INJECTION("Injection"),
    POWDER("Powder"),
    SPRAY("Spray"),
    SHAMPOO("Shampoo"),
    SOAP("Soap"),
    INHALER("Inhaler"),
    DEVICE("Device"),
    CONSUMABLE("Consumable"),
    OTHER("Other");

    companion object {
        fun fromLabel(label: String?): ProductCategory? {
            val normalized = label
                ?.uppercase(Locale.ROOT)
                ?.replace("[^A-Z0-9]+".toRegex(), " ")
                ?.trim()
                .orEmpty()
            if (normalized.isBlank()) return null

            return entries.firstOrNull { category ->
                normalized == category.name ||
                    normalized == category.displayName.uppercase(Locale.ROOT).replace("[^A-Z0-9]+".toRegex(), " ").trim()
            } ?: when (normalized) {
                "TABLET", "TABLETS", "CAPSULE", "CAPSULES", "CAP", "BOLUS" -> PILL
                "TONIC", "SYRUP", "LIQUID", "LIQUID SYRUP" -> TONIC
                "MEDICAL DEVICE", "EQUIPMENT" -> DEVICE
                else -> null
            }
        }
    }
}

object ProductCategoryClassifier {

    /** Image / UI model labels (8 classes) → database index buckets used when matching. */
    fun matchingCategories(filter: ProductCategory): Set<ProductCategory> = when (filter) {
        ProductCategory.TONIC -> setOf(ProductCategory.TONIC, ProductCategory.SUSPENSION)
        ProductCategory.CREAM -> setOf(ProductCategory.CREAM, ProductCategory.GEL, ProductCategory.OINTMENT)
        ProductCategory.PILL -> setOf(ProductCategory.PILL)
        ProductCategory.POWDER -> setOf(ProductCategory.POWDER)
        ProductCategory.INJECTION -> setOf(ProductCategory.INJECTION)
        ProductCategory.DROPS -> setOf(ProductCategory.DROPS)
        ProductCategory.LOTION -> setOf(ProductCategory.LOTION)
        ProductCategory.OTHER -> ProductCategory.entries.toSet()
        else -> setOf(filter)
    }

    private val pillTokens = setOf(
        "TAB", "TABS", "TABLET", "TABLETS", "DT", "MD", "ODT",
        "CAP", "CAPS", "CAPSULE", "CAPSULES", "SOFTGEL", "SOFTGELS", "SOFTLET",
        "BOLUS", "PESSARIES", "SUPPOSITORIES", "LOZENGES",
        "DUO", "FORTE", "SR", "XR", "XL", "RETARD", "CD", "DS", "LS", "OD", "BD"
    )
    private val tonicTokens = setOf("SYP", "SYRUP", "TONIC", "LIQ", "LIQUID", "ELIXIR", "SOLUTION", "SOL")
    private val suspensionTokens = setOf("SUSP", "SUSPENSION", "SUS")
    private val dropsTokens = setOf("DROPS", "DROP")
    private val lotionTokens = setOf("LOTION", "LOT", "LOATION")
    private val creamTokens = setOf("CREAM", "CREM", "CRM")
    private val gelTokens = setOf("GEL", "EMULGEL")
    private val ointmentTokens = setOf("OINT", "OINTMENT", "ONT", "OINMENT", "OINTEMENT")
    private val injectionTokens = setOf("INJ", "INJECT", "INJECTION", "VIAL", "AMP", "PFS", "INFU", "INFUSION", "IV")
    private val powderTokens = setOf(
        "POWDER", "PDR", "PWDR", "PDRS", "GRANULES", "GRANULE", "GRANUELS",
        "SACHET", "SACHETS", "CHOORNAM", "DUSTING"
    )
    private val sprayTokens = setOf("SPRAY", "SPRY")
    private val shampooTokens = setOf("SHAMPOO", "SHAMBOO")
    private val soapTokens = setOf("SOAP", "BAR")
    private val inhalerTokens = setOf("INHALER", "ROTAHALER", "TRANSHALER", "DPI", "MDI", "ROTACAPS", "RESPICAPS")
    private val deviceTokens = setOf(
        "APPARATUS", "MONITOR", "METER", "GLUCOMETER", "THERMOMETER", "NEBULIZER",
        "OXIMETER", "STETHOSCOPE", "SPACER", "WALKER", "STICK", "CHAIR", "PUMP",
        "BELT", "BRACE", "COLLAR", "SPLINT", "SUPPORT", "STOCKING", "CUSHION"
    )
    private val consumableTokens = setOf(
        "MASK", "SYRINGE", "NEEDLE", "STRIP", "LANCET", "BANDAGE", "GAUZE",
        "COTTON", "GLOVES", "DIAPER", "PAD", "WIPES", "BAG", "CATHETER",
        "TUBE", "DRESSING", "BLADE", "SUTURE", "THREAD"
    )

    fun classify(text: String?, source: ClassificationSource = ClassificationSource.OCR): ProductCategory {
        val value = text?.trim().orEmpty()
        if (value.isBlank()) return ProductCategory.OTHER

        val normalized = normalize(value)
        val tokens = MedicineRepository.tokenize(value).toSet()

        if (source == ClassificationSource.DATABASE && inferPillFromPackSize(normalized, tokens)) {
            return ProductCategory.PILL
        }

        return classifyFromTokens(normalized, tokens)
    }

    /**
     * Many DB rows omit TAB/CAP but include strip size (10'S, 15 S) and strength (MG).
     */
    private fun inferPillFromPackSize(normalized: String, tokens: Set<String>): Boolean {
        if (!STRIP_COUNT_PATTERN.containsMatchIn(normalized)) return false
        val hasStrength = tokens.any { it in STRENGTH_UNITS } ||
            normalized.contains(" MG") ||
            normalized.contains(" MCG")
        val hasLiquidForm = tokens.any { it in tonicTokens + suspensionTokens + dropsTokens + lotionTokens }
        return hasStrength && !hasLiquidForm
    }

    private fun classifyFromTokens(normalized: String, tokens: Set<String>): ProductCategory = when {
            containsAnyPhrase(normalized, "NASAL SPRAY", "MIST SPRAY") || hasAny(tokens, sprayTokens) -> ProductCategory.SPRAY
            containsAnyPhrase(normalized, "EYE OINT", "EYE OINTMENT") || hasAny(tokens, ointmentTokens) -> ProductCategory.OINTMENT
            containsAnyPhrase(normalized, "EYE DROPS", "EAR DROPS", "EYE DROP", "EAR DROP", "NASAL DROPS", "E/D", "E E", "N/D") ||
                hasAny(tokens, dropsTokens) -> ProductCategory.DROPS
            hasAny(tokens, injectionTokens) -> ProductCategory.INJECTION
            hasAny(tokens, inhalerTokens) || containsAnyPhrase(normalized, "ROTA CAPS", "TRANSCAPS") -> ProductCategory.INHALER
            hasAny(tokens, suspensionTokens) -> ProductCategory.SUSPENSION
            hasAny(tokens, powderTokens) ||
                containsAnyPhrase(
                    normalized,
                    "GRANULES FOR ORAL",
                    "GRANULES FOR ORAL SOLUTION",
                    "DUSTING POWDER",
                    "POWDER FOR"
                ) -> ProductCategory.POWDER
            hasAny(tokens, tonicTokens) || containsAnyPhrase(normalized, "ORAL SOLUTION", "ORAL SOL", "DRY SYP") -> ProductCategory.TONIC
            hasAny(tokens, lotionTokens) -> ProductCategory.LOTION
            hasAny(tokens, creamTokens) -> ProductCategory.CREAM
            hasAny(tokens, gelTokens) -> ProductCategory.GEL
            hasAny(tokens, shampooTokens) -> ProductCategory.SHAMPOO
            hasAny(tokens, soapTokens) -> ProductCategory.SOAP
            hasAny(tokens, pillTokens) || containsAnyPhrase(normalized, "SOFT GEL") -> ProductCategory.PILL
            hasAny(tokens, deviceTokens) -> ProductCategory.DEVICE
            hasAny(tokens, consumableTokens) -> ProductCategory.CONSUMABLE
            else -> ProductCategory.OTHER
    }

    private val STRENGTH_UNITS = setOf("MG", "MCG", "GM", "G")
    private val STRIP_COUNT_PATTERN = Regex("""\b\d{1,3}\s*'?S\b""")

    private fun normalize(text: String): String =
        text.uppercase(Locale.ROOT)
            .replace("[^A-Z0-9/]+".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

    private fun hasAny(tokens: Set<String>, candidates: Set<String>): Boolean =
        candidates.any { it in tokens }

    private fun containsAnyPhrase(text: String, vararg phrases: String): Boolean =
        phrases.any { phrase -> text.contains(phrase) }
}
