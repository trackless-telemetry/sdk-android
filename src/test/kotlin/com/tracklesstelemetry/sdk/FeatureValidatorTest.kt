package com.tracklesstelemetry.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [FeatureValidator].
 *
 * Covers:
 * - Test 13: Feature name validation
 * - Test 14: Invalid names silently ignored (validated at client level, tested here for rules)
 */
@DisplayName("FeatureValidator")
class FeatureValidatorTest {

    @Test
    @DisplayName("13. Valid feature names are accepted")
    fun validFeatureNamesAccepted() {
        assertTrue(FeatureValidator.isValid("export_clicked"))
        assertTrue(FeatureValidator.isValid("import-data"))
        assertTrue(FeatureValidator.isValid("a"))
        assertTrue(FeatureValidator.isValid("feature_123"))
        assertTrue(FeatureValidator.isValid("my-cool-feature"))
        assertTrue(FeatureValidator.isValid("x".repeat(100)))
    }

    @Test
    @DisplayName("Structured event names with dot grouping are accepted")
    fun structuredEventNamesAccepted() {
        assertTrue(FeatureValidator.isValid("theme.dark"))
        assertTrue(FeatureValidator.isValid("theme.light"))
        assertTrue(FeatureValidator.isValid("distance_preset.1_mile"))
        assertTrue(FeatureValidator.isValid("distance_preset.2_miles"))
        assertTrue(FeatureValidator.isValid("export-format.csv"))
    }

    @Test
    @DisplayName("14. Invalid feature names are rejected")
    fun invalidFeatureNamesRejected() {
        // Empty/null
        assertFalse(FeatureValidator.isValid(""))
        assertFalse(FeatureValidator.isValid(null))

        // Spaces
        assertFalse(FeatureValidator.isValid("invalid name with spaces"))

        // Uppercase
        assertFalse(FeatureValidator.isValid("ExportClicked"))
        assertFalse(FeatureValidator.isValid("EXPORT"))

        // Special characters
        assertFalse(FeatureValidator.isValid("invalid!"))
        assertFalse(FeatureValidator.isValid("invalid@name"))
        assertFalse(FeatureValidator.isValid("invalid#name"))
        assertFalse(FeatureValidator.isValid("invalid\$name"))

        // Too long
        assertFalse(FeatureValidator.isValid("x".repeat(101)))

        // Starts/ends with dot
        assertFalse(FeatureValidator.isValid(".export_clicked"))
        assertFalse(FeatureValidator.isValid("export_clicked."))

        // Consecutive dots
        assertFalse(FeatureValidator.isValid("export..clicked"))

        // Multiple dots are accepted by the regex
        assertTrue(FeatureValidator.isValid("a.b.c"))
        assertTrue(FeatureValidator.isValid("theme.dark.variant"))
    }

    @Test
    @DisplayName("UUID-like patterns are rejected")
    fun uuidPatternsRejected() {
        // Standard UUID with hyphens
        assertFalse(FeatureValidator.isValid("a1b2c3d4-e5f6-7890-abcd-ef1234567890"))

        // UUID with underscores
        assertFalse(FeatureValidator.isValid("a1b2c3d4_e5f6_7890_abcd_ef1234567890"))
    }

    @Test
    @DisplayName("Long hex sequences are rejected")
    fun longHexSequencesRejected() {
        // 25 hex chars in a row
        assertFalse(FeatureValidator.isValid("a".repeat(25)))

        // Entirely hex, > 16 chars
        assertFalse(FeatureValidator.isValid("abcdef0123456789a"))
    }

    @Test
    @DisplayName("Long numeric-only strings are rejected")
    fun longNumericStringsRejected() {
        // 13 digits
        assertFalse(FeatureValidator.isValid("1234567890123"))
    }

    @Test
    @DisplayName("Common English words that are valid hex are accepted")
    fun commonEnglishWordsAccepted() {
        // These are valid hex but should pass because they are short
        assertTrue(FeatureValidator.isValid("add"))
        assertTrue(FeatureValidator.isValid("badge"))
        assertTrue(FeatureValidator.isValid("cafe"))
        assertTrue(FeatureValidator.isValid("dead"))
        assertTrue(FeatureValidator.isValid("face"))
        assertTrue(FeatureValidator.isValid("feed"))
    }

    @Test
    @DisplayName("Feature names with underscores containing hex chars are accepted")
    fun featureNamesWithUnderscoresAndHexAccepted() {
        // Mixed hex and non-hex with underscores — common patterns
        assertTrue(FeatureValidator.isValid("cafe_fade_added"))
        assertTrue(FeatureValidator.isValid("event_20260301"))
    }

    @Test
    @DisplayName("Date-like prefixes are accepted")
    fun dateLikePrefixesAccepted() {
        // 8 digits with underscores — common date patterns
        assertTrue(FeatureValidator.isValid("event_20260301"))
        assertTrue(FeatureValidator.isValid("report_2026"))
    }

    @Test
    @DisplayName("Short numeric strings are accepted")
    fun shortNumericStringsAccepted() {
        // 12 digits or less are fine
        assertTrue(FeatureValidator.isValid("123456789012"))
        assertTrue(FeatureValidator.isValid("12345"))
    }

    // ─── PII Stripping ──────────────────────────────────────────────────────

    @Test
    @DisplayName("stripPII replaces email addresses with [REDACTED]")
    fun stripPIIEmail() {
        assertEquals("[REDACTED]", FeatureValidator.stripPII("user@example.com"))
        assertEquals("contact [REDACTED] now", FeatureValidator.stripPII("contact user@example.com now"))
    }

    @Test
    @DisplayName("stripPII replaces dashed SSN patterns with [REDACTED]")
    fun stripPIISsnDashed() {
        assertEquals("ssn [REDACTED] here", FeatureValidator.stripPII("ssn 123-45-6789 here"))
    }

    @Test
    @DisplayName("stripPII replaces 9-digit SSN patterns with [REDACTED]")
    fun stripPIISsnPlain() {
        assertEquals("id [REDACTED] entered", FeatureValidator.stripPII("id 123456789 entered"))
    }

    @Test
    @DisplayName("stripPII replaces phone numbers with [REDACTED]")
    fun stripPIIPhone() {
        assertEquals("called [REDACTED]", FeatureValidator.stripPII("called +1 (555) 123-4567"))
    }

    @Test
    @DisplayName("stripPII replaces unformatted phone numbers with [REDACTED]")
    fun stripPIIPhoneUnformatted() {
        assertEquals("number [REDACTED]", FeatureValidator.stripPII("number 5551234567"))
    }

    @Test
    @DisplayName("stripPII handles multiple PII patterns in one string")
    fun stripPIIMultiple() {
        val result = FeatureValidator.stripPII("email: a@b.com phone: 555-123-4567")
        assertFalse(result.contains("a@b.com"))
        assertFalse(result.contains("555-123-4567"))
    }

    @Test
    @DisplayName("stripPII leaves non-PII strings unchanged")
    fun stripPIINoChange() {
        assertEquals("dark_mode", FeatureValidator.stripPII("dark_mode"))
        assertEquals("export_clicked", FeatureValidator.stripPII("export_clicked"))
    }

    // ─── Normalization ─────────────────────────────────────────────────────

    @Test
    @DisplayName("normalize lowercases and replaces spaces with underscores")
    fun normalizeSpaces() {
        assertEquals("my_feature", FeatureValidator.normalize("My Feature"))
        assertEquals("product_details_page", FeatureValidator.normalize("Product Details Page"))
    }

    @Test
    @DisplayName("normalize replaces special characters with underscores")
    fun normalizeSpecialChars() {
        assertEquals("export_clicked", FeatureValidator.normalize("export!clicked"))
        assertEquals("feature_name_here", FeatureValidator.normalize("feature@name#here"))
    }

    @Test
    @DisplayName("normalize collapses consecutive dots")
    fun normalizeConsecutiveDots() {
        assertEquals("foo.bar", FeatureValidator.normalize("foo..bar"))
        assertEquals("a.b.c", FeatureValidator.normalize("a...b...c"))
    }

    @Test
    @DisplayName("normalize trims leading/trailing underscores and dots")
    fun normalizeTrimming() {
        assertEquals("foo", FeatureValidator.normalize("...foo..."))
        assertEquals("bar", FeatureValidator.normalize("___bar___"))
        assertEquals("foo", FeatureValidator.normalize("._foo._"))
    }

    @Test
    @DisplayName("normalize returns null for empty result")
    fun normalizeEmpty() {
        assertNull(FeatureValidator.normalize(""))
        assertNull(FeatureValidator.normalize("!!!"))
        assertNull(FeatureValidator.normalize("..."))
    }

    @Test
    @DisplayName("normalize returns null for abuse patterns")
    fun normalizeAbusePattern() {
        // 18 hex-only chars triggers the entirely-hex-and-long abuse check
        assertNull(FeatureValidator.normalize("abcdefabcdefabcdef"))
        assertNull(FeatureValidator.normalize("a".repeat(25)))
    }

    @Test
    @DisplayName("normalize truncates to max length")
    fun normalizeTruncation() {
        val result = FeatureValidator.normalize("x".repeat(150))
        assertEquals(100, result?.length)
    }

    @Test
    @DisplayName("normalize strips PII before normalizing")
    fun normalizePII() {
        assertEquals("redacted", FeatureValidator.normalize("user@example.com"))
    }

    @Test
    @DisplayName("normalize preserves valid strings")
    fun normalizeValidStrings() {
        assertEquals("export_clicked", FeatureValidator.normalize("export_clicked"))
        assertEquals("theme.dark", FeatureValidator.normalize("theme.dark"))
        assertEquals("my-feature", FeatureValidator.normalize("my-feature"))
    }
}
