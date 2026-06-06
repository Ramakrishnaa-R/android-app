package com.fosautomations.pharmacam

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun testCleanOcrText() {
        val input = "Raxo:20 Rabeprazole Tab efs iP narmEasy"
        val result = NumericOcrCorrector.cleanOcrText(input)
        println("cleanOcrText result: $result")
        assertTrue(result.contains("RAZO"))
        assertTrue(result.contains("20"))
        assertTrue(result.contains("RABEPRAZOLE"))
        assertFalse(result.contains("NARMEASY"))
        assertFalse(result.contains("ARMEASY"))
        assertFalse(result.contains("RAXO"))
    }

    @Test
    fun testPreprocessInput() {
        // Test that preprocessInput correctly processes and corrects RAXO 20 without creating RAXOZO
        val result = Matcher.preprocessForMatch("RAXO 20 RABEPRAZOLE")
        println("preprocessForMatch result: $result")
        assertFalse(result.contains("RAXOZO"))
        assertFalse(result.contains("RAZOZO"))
        assertTrue(result.contains("RAZO"))
        assertFalse(result.contains("20")) // digits should be filtered out from tokens
    }

    @Test
    fun testBuildSearchQuery() {
        val input = "Raxo:20 Rabeprazole Tab efs iP narmEasy"
        val query = MedicineNameResolver.buildSearchQuery(input)
        println("buildSearchQuery result: $query")
        assertTrue(query.contains("RAZO"))
        assertTrue(query.contains("20"))
    }

    @Test
    fun `REZO corrects to RAZO`() {
        val corrected = NumericOcrCorrector.cleanOcrText("REZO 20")
        assertTrue(
            "Expected 'RAZO' in corrected text but got: $corrected",
            corrected.contains("RAZO")
        )
    }

    @Test
    fun `brand strength extracted from spaced string not compact`() {
        // This is the exact failing case from the screenshot
        val ocrRaw    = "RABEPRAZ ETS RAZO 20"   // after REZO→RAZO correction
        val compact   = ocrRaw.replace(" ", "")   // "RABEPRAZETSRAZO20"
        
        val result = MedicineNameResolver.extractBrandStrengthFromCompact(
            spaced  = ocrRaw,
            compact = compact
        )
        assertEquals(
            "Expected RAZO 20 but got: $result",
            "RAZO 20",
            result
        )
    }

    @Test
    fun `compact string alone would have produced wrong result`() {
        // Documents the original bug — greedy match on compact
        val compact = "RABEPRAZETSRAZO20"
        val greedyRegex = Regex("""([A-Z]{3,10})(20)\b""")
        val badMatch = greedyRegex.find(compact)?.groupValues?.get(1)
        // This would have been "RAZETSRAZO" — confirming the bug
        assertNotEquals("RAZO", badMatch)
    }

    @Test
    fun `DOLO 650 still matches correctly`() {
        val result = MedicineNameResolver.extractBrandStrengthFromCompact(
            spaced  = "DOLO 650 TAB",
            compact = "DOLO650TAB"
        )
        assertEquals("DOLO 650", result)
    }

    @Test
    fun `AUGMENTIN 625 still matches correctly`() {
        val result = MedicineNameResolver.extractBrandStrengthFromCompact(
            spaced  = "AUGMENTIN 625 DUO TAB",
            compact = "AUGMENTIN625DUOTAB"
        )
        assertEquals("AUGMENTIN 625", result)
    }

    @Test
    fun `extractBrandKey extracts correct key from hyphenated brand`() {
        val key = Matcher.extractBrandKey("RAZO-20 TAB 15 S")
        assertEquals("RAZO", key)
    }

    @Test
    fun `extractBrandKey extracts correct key from slash brand`() {
        val key = Matcher.extractBrandKey("AMOXICILLIN/CLAVULANIC ACID")
        assertEquals("AMOXICILLIN", key)
    }

    @Test
    fun `extractBrandKey fuses short prefixes`() {
        assertEquals("TBACT", Matcher.extractBrandKey("T-BACT OINT 15 GM"))
        assertEquals("TBACT", Matcher.extractBrandKey("T BACT OINT 5GM"))
        assertEquals("ARET", Matcher.extractBrandKey("A-RET GEL"))
        assertEquals("SPBACT", Matcher.extractBrandKey("SP BACT 10 MG OINT"))
    }

    @Test
    fun `tokenize splits on hyphens and slashes`() {
        val tokens = Matcher.tokenize("RAZO-20 TAB 15 S")
        assertTrue(tokens.contains("RAZO"))
        assertFalse(tokens.contains("RAZO-20"))
        assertTrue(tokens.contains("RAZO20"))
    }

    @Test
    fun `extractBrandStrengthFromCompact supports long brand names`() {
        val result = MedicineNameResolver.extractBrandStrengthFromCompact(
            spaced  = "THROMBOPHOB 30 G",
            compact = "THROMBOPHOB30G"
        )
        assertEquals("THROMBOPHOB 30", result)
    }

    @Test
    fun `tokenize preserves hyphenated concatenated version`() {
        val tokens = Matcher.tokenize("T-BACT OINT 15GM")
        assertTrue("Expected to contain 'TBACT' in $tokens", tokens.contains("TBACT"))
        assertTrue("Expected to contain 'BACT' in $tokens", tokens.contains("BACT"))
        assertTrue("Expected to contain 'OINT' in $tokens", tokens.contains("OINT"))
    }

    @Test
    fun `tokenize fuses short tokens separated by space`() {
        val tokens = Matcher.tokenize("T BACT OINT 15GM")
        assertTrue("Expected to contain 'TBACT' in $tokens", tokens.contains("TBACT"))
        assertTrue("Expected to contain 'BACT' in $tokens", tokens.contains("BACT"))
    }

    @Test
    fun `MedicineNameResolver builds search query containing TBACT or T-BACT`() {
        MedicineRepository.loadForTest(listOf("T-BACT OINT 15 GM"))
        Matcher.buildIndex()
        val input = "Muptrocin Qinment RE T-bact Ointment Now In amitube om dhaet sunilght at a"
        val query = MedicineNameResolver.buildSearchQuery(input)
        println("buildSearchQuery for T-BACT result: $query")
        assertTrue("Query '$query' should contain TBACT or T-BACT", query.contains("TBACT") || query.contains("T-BACT") || query.contains("OINT BACT") || query.contains("BACT OINT"))
    }

    @Test
    fun `MedicineNameResolver resolves T-BACT correctly`() {
        MedicineRepository.loadForTest(listOf("T-BACT OINT 15 GM"))
        Matcher.buildIndex()
        val input = "Muptrocin Qinment RE T-bact Ointment Now In amitube om dhaet sunilght at a"
        val resolved = MedicineNameResolver.resolve(input, emptySet())
        println("resolve result: ${resolved.medicine?.name} score: ${resolved.score}")
        assertNotNull(resolved.medicine)
        assertEquals("T-BACT OINT 15 GM", resolved.medicine?.name)
        assertTrue("Resolved score is too low: ${resolved.score}", resolved.score >= 55.0)
    }
}