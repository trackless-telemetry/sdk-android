package com.tracklesstelemetry.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The cross-SDK normalization table: input, then what is stored, or null when it is rejected.
 * field-normalization-parity.test.ts in the monorepo fails if these rows drift from its own.
 */
private val NORMALIZATION_PARITY_TABLE: List<Pair<String, String?>> = listOf(
    "123e4567-e89b-12d3-a456-426614174000" to null,
    "123E4567-E89B-12D3-A456-426614174000" to null,
    "550e8400_e29b_41d4_a716_446655440000" to null,
    "550e8400.e29b.41d4.a716.446655440000" to null,
    "550e8400 e29b 41d4 a716 446655440000" to null,
    "user-123e4567-e89b-12d3-a456-426614174000" to null,
    "err.123e4567-e89b-12d3-a456-426614174000" to null,
    "user-abcdefab-cdef-abcd-efab-cdefabcdefab" to null,
    "abcdefab-cdef-abcd-efab-cdefabcdefab_retry" to null,
    "checkout_flow_step_checkout_flow_step_checkout_flow_step_checkout_flow_step_checkout_flow_step_123e4567-e89b-12d3-a456-426614174000" to null,
    "507f1f77bcf86cd799439011" to null,
    "550e8400e29b41d4a716446655440000" to null,
    "tok_507f1f77bcf86cd799439011" to null,
    "tok-abcdefabcdefabcdefabcdef" to null,
    "tok_550e8400e29b41d4a716446655440000" to null,
    "tok-abcdefabcdefabcdefabcde" to "tok-abcdefabcdefabcdefabcde",
    "abcdefabcdefabcde" to null,
    "1234567890123" to null,
    "123e4567-e89b-12d3-a_redacted" to null,
    "550e8400e29b41d4a_redacted" to null,
    "user-6fe8b8e2-0bd_redacted_aeb89f4d624" to null,
    "tok_94efbda267a_redacted_a9061dd2" to null,
    "deadbeef-555-123-4567" to null,
    "555-123-4567" to "redacted",
    "+1 (555) 123-4567" to "redacted",
    "call 555-123-4567" to "call_redacted",
    "err.555-123-4567" to "err._redacted",
    "user@example.com" to "redacted",
    "signup_failed user@example.com" to "signup_failed_redacted",
    "123-45-6789" to "redacted",
    "settings.theme" to "settings.theme",
    "err_001" to "err_001",
    "java.lang.illegalstateexception" to "java.lang.illegalstateexception",
    "java.lang.IllegalStateException" to "java.lang.illegalstateexception",
    "Sign Up Button" to "sign_up_button",
    "cafe_fade_added" to "cafe_fade_added",
    "add_face_redacted" to "add_face_redacted",
    "123e4567" to "123e4567",
    "e10b90f6--b4f5--4c42--b4cf--317b5d116211" to null,
    "c2008c33__92bc__48b7__b93f__c0a61d76418a" to null,
    "1cae8043 - 4d24 - 4e22 - 956f - 698a933e3b5b" to null,
    "9b7ee1c2-9299-4ec0-af1e-c102392226c" to null,
    "7b59744c-d299-44ad-b30d" to null,
    "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.y" to "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
    "deadbeefdead_555-123-4567" to null,
    "555-123-4567_12345678" to null,
    "trackless.uncaught" to "trackless.uncaught",
    "NSInvalidArgumentException" to "nsinvalidargumentexception",
    "TypeError" to "typeerror",
    "com.example.app.Checkout\$PaymentException" to "com.example.app.checkout_paymentexception",
    "!!!" to null,
    // An address may hold non-ASCII characters, but not a Unicode space; the rest of the value stays.
    "éric@example.com" to "redacted",
    "日本user@example.com" to "redacted",
    "user@example.comé" to "redacted",
    "signup é user@example.com" to "signup_redacted",
    "us\u0301er@example.com" to "redacted",
    "uſer@example.com" to "redacted",
    "\u212Aevin@example.com" to "redacted",
    "user@exämple.com" to "redacted",
    "user@\u0301example.com" to "redacted",
    "josé@example.com" to "redacted",
    "user＠example.com" to "user_example.com",
    "user@localhost" to "user_localhost",
    "user@example.c0m" to "user_example.c0m",
    "日本語@example.com" to "redacted",
    "user@example.みんな" to "redacted",
    "user@example.com_x" to "redacted__x",
    "user@example.com2" to "redacted_2",
    "a@b.com_c@d.com" to "redacted_redacted",
    "login失敗user@example.com" to "redacted",
    "clicked\u3000user@example.com" to "clicked_redacted",
    "user@example.com\u00A0clicked" to "redacted_clicked",
    "abc😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀@example.com" to "redacted",
    "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx@example.com" to "xxxxxx_redacted",
    "a@b.com-c@d.com" to "redacted_redacted",
    "x .foo@example.com" to "x_redacted",
    "é123456789" to "redacted",
    "123456789é" to "redacted",
    "日本123-45-6789" to "redacted",
    "123-45-6789é" to "redacted",
    "é555-123-4567" to "redacted",
    // Non-ASCII digits are not digits to the patterns, and sanitizing removes them.
    "٠١٢٣٤٥٦٧٨" to null,
    "１２３４５６７８９" to null,
    "𝟏𝟐𝟑𝟒𝟓𝟔𝟕𝟖𝟗" to null,
    "٠١٢-٣٤-٥٦٧٨" to "-_-",
    "call ٠١٢٣٤٥٦٧٨" to "call",
    "12345٠67890" to "12345_67890",
    "٠123456789" to "redacted",
    // Unicode spaces separate a phone number; other invisible characters do not.
    "555\u00A0123\u00A04567" to "redacted",
    "555\uFEFF123\uFEFF4567" to "redacted",
    "555\u202F123\u202F4567" to "redacted",
    "555\u3000123\u30004567" to "redacted",
    "555\u000B123\u000B4567" to "redacted",
    "555\u0085123\u00854567" to "555_123_4567",
    "555\u200B123\u200B4567" to "555_123_4567",
)

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

    @Test
    @DisplayName("The email pattern's limits keep a long value cheap")
    fun longValues() {
        for (value in listOf(
            "a".repeat(200_000),
            "日".repeat(200_000),
            "a@" + "b".repeat(200_000),
            "a@b".repeat(70_000),
        )) {
            assertEquals(value, FeatureValidator.stripPII(value))
        }
    }

    @Test
    @DisplayName("Normalization matches the cross-SDK table")
    fun normalizationParity() {
        for ((input, stored) in NORMALIZATION_PARITY_TABLE) {
            assertEquals(stored, FeatureValidator.normalize(input), "input: $input")
        }
    }
}
