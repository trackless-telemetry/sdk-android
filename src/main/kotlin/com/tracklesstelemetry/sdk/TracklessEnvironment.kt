package com.tracklesstelemetry.sdk

/**
 * Environment tag for distinguishing test traffic from production traffic.
 *
 * Auto-detected from [android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE]:
 * - Debuggable builds -> [SANDBOX]
 * - Non-debuggable builds -> [PRODUCTION]
 *
 * Can be explicitly overridden via [TracklessConfig.environment].
 */
enum class TracklessEnvironment(val value: String) {
    SANDBOX("sandbox"),
    PRODUCTION("production");
}
