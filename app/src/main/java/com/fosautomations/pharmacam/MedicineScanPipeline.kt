package com.fosautomations.pharmacam

import android.util.Log

/**
 * End-to-end scan logic:
 * 1. Classify package type from **camera image** (8 classes).
 * 2. **Fuzzy-match** OCR text inside that category slice of [medicines.json] only.
 * 3. If the image model is **not sure**, use **OCR type words** for the category instead.
 */
object MedicineScanPipeline {

    private const val TAG = "PharmaCam_Scan"

    /** Minimum softmax score to trust image top-1. */
    const val IMAGE_SURE_CONFIDENCE = 0.50f

    /** Top-1 must beat top-2 by at least this margin (else → not sure). */
    const val IMAGE_SURE_MARGIN = 0.10f

    data class CategoryDecision(
        val category: ProductCategory,
        val source: String,
        val fromImage: Boolean,
        val applyCategoryFilter: Boolean,
        val imageConfidence: Float?,
        val ocrTextCategory: ProductCategory
    )

    data class MatchRequest(
        val ocrText: String,
        val category: CategoryDecision,
        /** Null = search entire medicines.json. */
        val categoryFilter: ProductCategory?,
        val poolSize: Int
    )

    /**
     * Step 1 — Decide category: image when sure, else OCR label keywords, else weak image / full DB.
     */
    fun decideCategory(
        image: MedicineCategoryImageClassifier.Prediction?,
        ocrText: String
    ): CategoryDecision {
        val ocrCategory = ProductCategoryClassifier.classify(ocrText, ClassificationSource.OCR)
        val ocrUsable = ocrCategory != ProductCategory.OTHER

        if (image == null) {
            Log.i(TAG, "No image model → category from OCR: $ocrCategory")
            return CategoryDecision(
                category = ocrCategory,
                source = "ocr_no_model",
                fromImage = false,
                applyCategoryFilter = ocrUsable,
                imageConfidence = null,
                ocrTextCategory = ocrCategory
            )
        }

        logImagePrediction(image)

        if (isImageSure(image)) {
            Log.i(
                TAG,
                "Image sure → ${image.category} (${(image.confidence * 100).toInt()}%) " +
                    "— fuzzy match in this group only"
            )
            return CategoryDecision(
                category = image.category,
                source = "image_sure",
                fromImage = true,
                applyCategoryFilter = true,
                imageConfidence = image.confidence,
                ocrTextCategory = ocrCategory
            )
        }

        if (ocrUsable) {
            val reason = when {
                image.category == ProductCategory.OTHER -> "image_other"
                image.confidence < IMAGE_SURE_CONFIDENCE -> "image_low_conf"
                else -> "image_ambiguous"
            }
            Log.i(
                TAG,
                "Image not sure ($reason: ${image.category} ${(image.confidence * 100).toInt()}%) " +
                    "→ category from OCR: $ocrCategory"
            )
            return CategoryDecision(
                category = ocrCategory,
                source = "ocr_$reason",
                fromImage = false,
                applyCategoryFilter = true,
                imageConfidence = image.confidence,
                ocrTextCategory = ocrCategory
            )
        }

        if (image.category != ProductCategory.OTHER && image.confidence >= 0.30f) {
            Log.i(TAG, "No OCR type word → weak image category ${image.category}")
            return CategoryDecision(
                category = image.category,
                source = "image_weak_no_ocr_type",
                fromImage = true,
                applyCategoryFilter = true,
                imageConfidence = image.confidence,
                ocrTextCategory = ocrCategory
            )
        }

        Log.i(TAG, "Image not sure and no OCR type → fuzzy match in full medicines.json")
        return CategoryDecision(
            category = ProductCategory.OTHER,
            source = "no_category_filter",
            fromImage = false,
            applyCategoryFilter = false,
            imageConfidence = image.confidence,
            ocrTextCategory = ocrCategory
        )
    }

    /**
     * Step 2 — Build matcher input: OCR text + optional category filter from [decideCategory].
     */
    fun buildMatchRequest(ocrText: String, category: CategoryDecision): MatchRequest {
        val filter = if (category.applyCategoryFilter && category.category != ProductCategory.OTHER) {
            category.category
        } else {
            null
        }
        val pool = if (filter != null) {
            MedicineRepository.getByCategoryFilter(filter)
        } else {
            MedicineRepository.getDatabase()
        }
        Log.i(
            TAG,
            "Match pool: ${pool.size} medicines " +
                "(filter=${filter?.displayName ?: "ALL"}, source=${category.source})"
        )
        return MatchRequest(
            ocrText = ocrText,
            category = category,
            categoryFilter = filter,
            poolSize = pool.size
        )
    }

    /**
     * Step 3 — Fuzzy match inside the group; optionally widen if empty.
     */
    fun findMatches(
        request: MatchRequest,
        blacklist: Set<String>,
        maxResults: Int = 3,
        allowFullDatabaseFallback: Boolean = true
    ): List<Matcher.ScoredMatch> {
        var matches = Matcher.findTopMatches(
            request.ocrText,
            blacklist,
            maxResults,
            categoryFilter = request.categoryFilter
        )
        if (matches.isNotEmpty() || request.categoryFilter == null || !allowFullDatabaseFallback) {
            return matches
        }

        Log.w(
            TAG,
            "No matches in ${request.categoryFilter.displayName} " +
                "(${request.poolSize} items) — retrying full medicines.json"
        )
        return Matcher.findTopMatches(request.ocrText, blacklist, maxResults, categoryFilter = null)
    }

    fun isImageSure(image: MedicineCategoryImageClassifier.Prediction): Boolean {
        if (image.category == ProductCategory.OTHER) return false
        if (image.confidence < IMAGE_SURE_CONFIDENCE) return false
        val second = image.topK.getOrNull(1)?.second ?: 0f
        return (image.confidence - second) >= IMAGE_SURE_MARGIN
    }

    private fun logImagePrediction(image: MedicineCategoryImageClassifier.Prediction) {
        val top = image.topK.joinToString { (cat, score) ->
            "${cat.displayName}=${(score * 100).toInt()}%"
        }
        Log.i(
            TAG,
            "Image top-1: ${image.category.displayName} ${(image.confidence * 100).toInt()}% " +
                "sure=${isImageSure(image)} | $top"
        )
    }
}
