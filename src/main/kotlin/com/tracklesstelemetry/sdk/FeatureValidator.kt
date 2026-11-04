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

    /** 24 or more consecutive hex characters anywhere in the value */
    private val LONG_HEX_REGEX = Regex("[0-9a-f]{24,}")

    /** Numeric-only strings > 12 characters */
    private val LONG_NUMERIC_REGEX = Regex("^[0-9]{13,}$")

    /**
     * A UUID anywhere in the value, by its first four groups (8-4-4-4) so one with its
     * tail cut off is found too, with any run of hyphens, underscores or dots between them
     */
    private val UUID_REGEX = Regex(
        "[0-9a-f]{8}[-_.]+[0-9a-f]{4}[-_.]+[0-9a-f]{4}[-_.]+[0-9a-f]{4}"
    )

    /** Entirely hex characters (no underscores, no [g-z]) and longer than 16 characters */
    private val ALL_HEX_REGEX = Regex("^[0-9a-f]{17,}$")

    private val RESIDUE_SPLIT_REGEX = Regex("redacted|[-_.]+")
    private val HEX_TOKEN_REGEX = Regex("^[0-9a-f]+$")

    /**
     * JavaScript's `\b`, which only ASCII letters, digits and `_` count for. Android's regex is
     * ICU: its `\b`, `\d`, `\s` and case folding take in non-ASCII characters and the web SDK's
     * do not, so none of them appear in these patterns: `é123456789` would keep its digits. The
     * JVM the unit tests run on differs from both, so a pass there cannot show
     * these are right on a device; the patterns are written to mean one thing everywhere.
     */
    private const val ASCII_WORD_BOUNDARY =
        "(?:(?<=[A-Za-z0-9_])(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])(?=[A-Za-z0-9_]))"

    /** Every non-ASCII character but the Unicode spaces [PHONE_REGEX] lists can be part of an address. */
    private const val EMAIL_NON_ASCII = "\\u00A1-\\u167F\\u1681-\\u1FFF\\u200B-\\u2027\\u202A-\\u202E\\u2030-\\u205E\\u2060-\\u2FFF\\u3001-\\uFEFE\\uFF00-\\x{10FFFF}"

    // PII patterns. The email limits are the RFC's, and what keeps it linear with no boundary to start from.
    private val EMAIL_REGEX = Regex(
        "[A-Za-z0-9._%+\\-$EMAIL_NON_ASCII]{1,64}@[A-Za-z0-9.\\-$EMAIL_NON_ASCII]{1,255}\\.[A-Za-z$EMAIL_NON_ASCII]{2,}"
    )
    private val SSN_DASHED_REGEX = Regex(ASCII_WORD_BOUNDARY + "[0-9]{3}-[0-9]{2}-[0-9]{4}" + ASCII_WORD_BOUNDARY)
    private val SSN_PLAIN_REGEX = Regex(ASCII_WORD_BOUNDARY + "[0-9]{9}" + ASCII_WORD_BOUNDARY)
    private val PHONE_REGEX = Regex(
        "\\+?[0-9][0-9\\t\\n\\x0B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF\\-.()]{8,}[0-9]"
    )

    /**
     * Strip PII patterns (emails, SSNs, phone numbers) from a string,
     * replacing matches with [REDACTED].
     */
    fun stripPII(value: String): String {
        var result = value
        // Email addresses
        if ('@' in result) result = EMAIL_REGEX.replace(result, "[REDACTED]")
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
     *
     * The abuse patterns run on the whole value before the PII strip as well as on
     * the result: the phone pattern eats runs of digits inside a UUID or hex ID, and
     * truncating can cut one short, either of which leaves a part no rule matches.
     */
    fun normalize(value: String): String? {
        if (isAbusePattern(sanitize(value))) return null
        var result = sanitize(stripPII(value))
        if (result.isEmpty()) return null
        // Truncate to max length; a cut can leave a trailing '.', which ingest rejects
        if (result.length > MAX_LENGTH) result = TRIM_REGEX.replace(result.take(MAX_LENGTH), "")
        // Reject abuse patterns
        if (isAbusePattern(result)) return null
        return result
    }

    /** Lowercases and reduces a value to the characters and shape a field allows. */
    private fun sanitize(value: String): String {
        // Replace runs of non-allowed chars with a single underscore
        var result = INVALID_CHARS_REGEX.replace(value.lowercase(), "_")
        // Trim leading/trailing underscores and dots
        result = TRIM_REGEX.replace(result, "")
        // Collapse consecutive dots
        return CONSECUTIVE_DOTS_REGEX.replace(result, ".")
    }

    /**
     * Returns true if the name matches anti-identifier abuse patterns.
     */
    private fun isAbusePattern(name: String): Boolean {
        if (LONG_HEX_REGEX.containsMatchIn(name)) return true
        if (LONG_NUMERIC_REGEX.matches(name)) return true
        if (UUID_REGEX.containsMatchIn(name)) return true
        if (ALL_HEX_REGEX.matches(name)) return true
        if (hasRedactedIdResidue(name)) return true
        return false
    }

    /** The PII marker beside 8+ characters of hex-only tokens: what remains of an ID. */
    private fun hasRedactedIdResidue(name: String): Boolean {
        if (!name.contains("redacted")) return false
        val hexChars = RESIDUE_SPLIT_REGEX.split(name)
            .filter { HEX_TOKEN_REGEX.matches(it) }
            .sumOf { it.length }
        return hexChars >= 8
    }
}
