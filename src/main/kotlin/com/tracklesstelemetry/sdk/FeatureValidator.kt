package com.tracklesstelemetry.sdk

/**
 * Validates feature names against the Trackless naming rules.
 *
 * Rules:
 * - Lowercase alphanumeric, underscores, hyphens, and dots: [a-z0-9_.-]
 * - 1-100 characters
 * - Must not start or end with '.'
 * - No consecutive dots ('..')
 * - No leading, trailing, or consecutive dots
 * - Must not look like a UUID, hash, or encoded identifier
 */
internal object FeatureValidator {

    private const val MAX_LENGTH = 100

    /**
     * Matches valid feature names:
     * - One or more [a-z0-9_-] characters
     * - Optionally followed by any number of dot-separated segments
     */
    private val FEATURE_NAME_REGEX = Regex("^[a-z0-9_-]+(\\.[a-z0-9_-]+)*$")

    /** Consecutive hex characters > 24 */
    private val LONG_HEX_REGEX = Regex("[0-9a-f]{25,}")

    /** Numeric-only strings > 12 characters */
    private val LONG_NUMERIC_REGEX = Regex("^[0-9]{13,}$")

    /** UUID pattern with hyphens or underscores */
    private val UUID_REGEX = Regex(
        "[0-9a-f]{8}[-_][0-9a-f]{4}[-_][0-9a-f]{4}[-_][0-9a-f]{4}[-_][0-9a-f]{12}"
    )

    /** Entirely hex characters (no underscores, no [g-z]) and longer than 16 characters */
    private val ALL_HEX_REGEX = Regex("^[0-9a-f]{17,}$")

    // PII patterns
    private val EMAIL_REGEX = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b", RegexOption.IGNORE_CASE)
    private val SSN_DASHED_REGEX = Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b")
    private val SSN_PLAIN_REGEX = Regex("\\b\\d{9}\\b")
    private val PHONE_REGEX = Regex("\\+?\\d[\\d\\s\\-.()]{8,}\\d")

    /**
     * Strip PII patterns (emails, SSNs, phone numbers) from a string,
     * replacing matches with [REDACTED].
     */
    fun stripPII(value: String): String {
        var result = value
        // Email addresses
        result = EMAIL_REGEX.replace(result, "[REDACTED]")
        // SSN patterns (check before phone numbers to avoid false matches)
        result = SSN_DASHED_REGEX.replace(result, "[REDACTED]")
        result = SSN_PLAIN_REGEX.replace(result, "[REDACTED]")
        // Phone numbers
        result = PHONE_REGEX.replace(result, "[REDACTED]")
        return result
    }

    /** Regex to match runs of non-allowed characters */
    private val INVALID_CHARS_REGEX = Regex("[^a-z0-9._-]+")

    /** Regex to match consecutive dots */
    private val CONSECUTIVE_DOTS_REGEX = Regex("\\.{2,}")

    /** Regex to match leading/trailing underscores and dots */
    private val TRIM_REGEX = Regex("^[_.]+|[_.]+$")

    /**
     * Returns true if the feature name is valid.
     */
    fun isValid(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        if (name.length > MAX_LENGTH) return false
        if (!FEATURE_NAME_REGEX.matches(name)) return false
        if (isAbusePattern(name)) return false
        return true
    }

    /**
     * Normalize a field value (name, detail, step, code) into a valid format.
     * Strips PII, lowercases, replaces invalid chars with underscores, and validates.
     * Returns null if the result is empty or matches an abuse pattern.
     */
    fun normalize(value: String): String? {
        var result = stripPII(value).lowercase()
        // Replace runs of non-allowed chars with a single underscore
        result = INVALID_CHARS_REGEX.replace(result, "_")
        // Trim leading/trailing underscores and dots
        result = TRIM_REGEX.replace(result, "")
        // Collapse consecutive dots
        result = CONSECUTIVE_DOTS_REGEX.replace(result, ".")
        if (result.isEmpty()) return null
        // Truncate to max length
        if (result.length > MAX_LENGTH) result = result.take(MAX_LENGTH)
        // Reject abuse patterns
        if (isAbusePattern(result)) return null
        return result
    }

    /**
     * Returns true if the name matches anti-identifier abuse patterns.
     */
    private fun isAbusePattern(name: String): Boolean {
        if (LONG_HEX_REGEX.containsMatchIn(name)) return true
        if (LONG_NUMERIC_REGEX.matches(name)) return true
        if (UUID_REGEX.containsMatchIn(name)) return true
        if (ALL_HEX_REGEX.matches(name)) return true
        return false
    }
}
