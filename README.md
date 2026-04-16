# Trackless Telemetry Android SDK

Privacy-first analytics for Android apps. Record what features your users use — without tracking who they are.

Trackless collects **aggregate usage counts** with coarse device context. No user identifiers. No fingerprinting. No persistent storage. Fully compliant with GDPR, CCPA, PECR, and ePrivacy — with nothing to consent to.

## Requirements

- Android API 24+ (Android 7.0)
- Java 17+
- Kotlin 1.9+

## Installation

### Gradle (Kotlin DSL)

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.tracklesstelemetry:sdk-android:0.2.3")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.tracklesstelemetry:sdk-android:0.2.3'
}
```

## Quick Start

```kotlin
import com.tracklesstelemetry.sdk.Trackless
import com.tracklesstelemetry.sdk.TracklessConfig
import com.tracklesstelemetry.sdk.ErrorSeverity

// Initialize once (e.g., in your Application.onCreate)
Trackless.configure(
    context = applicationContext,
    config = TracklessConfig(
        apiKey = "tl_your_api_key_here"
    )
)

// Record events anywhere in your app
Trackless.view("home")
Trackless.view("settings", "notifications")
Trackless.feature("export_clicked")
Trackless.feature("export_clicked", "csv")
Trackless.funnel("checkout", 0, "view_cart")
Trackless.performance("api_fetch", durationSeconds = 0.342)
Trackless.error("payment_failed", severity = ErrorSeverity.ERROR, code = "DECLINED")
```

## API Reference

### Configuration

```kotlin
// Simple — just an API key with default settings
Trackless.configure(
    context = applicationContext,
    config = TracklessConfig(
        apiKey = "tl_your_api_key_here"
    )
)

// All options
Trackless.configure(
    context = applicationContext,
    config = TracklessConfig(
        apiKey = "tl_your_api_key_here",
        endpoint = "https://custom.api.com",                    // Optional — defaults to https://api.tracklesstelemetry.com
        environment = TracklessEnvironment.SANDBOX,              // Optional — auto-detected from build config
        enabled = true,                                          // Optional — disable to suppress all recording
        onError = { error -> Log.w("Trackless", error) },       // Optional — callback for debugging
        flushIntervalSeconds = 60L,                              // Optional — how often buffered events are sent
        debugLogging = false,                                    // Optional — enable debug logging for happy-path events
        suppressWarnings = false,                                // Optional — suppress warning and error logging
    )
)
```

**Environment auto-detection:** Debug builds (with `FLAG_DEBUGGABLE`) automatically use `SANDBOX`, release builds use `PRODUCTION`. Override by passing `environment` explicitly.

**App version auto-detection:** `appVersion` and `buildNumber` are automatically read from `PackageManager`.

### Event Methods

All methods are static, non-blocking, non-throwing, and safe to call from any thread.

| Method                                                                                  | Description                         |
| --------------------------------------------------------------------------------------- | ----------------------------------- |
| `Trackless.view(name: String, detail: String? = null)`                                  | View event (optional detail)        |
| `Trackless.feature(name: String, detail: String? = null)`                               | Feature interaction (optional detail) |
| `Trackless.funnel(funnelName: String, stepIndex: Int, stepName: String)`                 | Funnel step progression             |
| `Trackless.performance(name: String, durationSeconds: Double, thresholdSeconds: Double?)` | Timing measurement (seconds)        |
| `Trackless.error(name: String, severity: ErrorSeverity, code: String?)`                  | Application error                   |

### Control Methods

```kotlin
Trackless.isConfigured         // Check if SDK is ready (useful in shared code)

Trackless.setEnabled(false)    // Stop recording, discard buffer
Trackless.setEnabled(true)     // Resume recording

Trackless.flush()              // Force-send buffered events
Trackless.destroy()            // Flush and permanently disable
```

## Event Naming Rules

All event fields (`name`, `detail`, `step`, `code`) are automatically normalized:

- **Auto-normalize:** spaces and invalid characters are replaced with `_` (`Sign Up Button` -> `sign_up_button`)
- **Auto-lowercase:** fields are lowercased (`Export_Clicked` -> `export_clicked`)
- **Trim/collapse:** leading/trailing `_`/`.` trimmed, consecutive dots collapsed
- **Truncate:** fields are truncated to 100 characters
- **Dots:** dots allowed for hierarchical grouping (e.g., `settings.theme`, `nav.settings.display`)
- **No identifiers:** UUIDs, long hex strings, and long numeric strings are rejected
- **PII stripping:** emails, phone numbers, and SSN patterns are stripped from all fields

## How It Works

1. **Buffering** — Events are aggregated in memory. Duplicate events increment a counter rather than creating separate entries.
2. **Periodic flush** — Every 60 seconds (configurable), the buffer is sent to the ingest endpoint as a batch.
3. **Background flush** — The SDK flushes when the app enters the background via `ActivityLifecycleCallbacks`.
4. **Session management** — Sessions start on configure and on each foreground return, end on background with immediate flush.
5. **Circuit breaker** — Server errors trigger exponential backoff (30s -> 60s -> 5m -> 15m -> 60m).
6. **Bounded memory** — Buffer holds up to 1,000 unique entries. Beyond that, new entries are silently dropped.

## Context Collected

The SDK captures a small set of **coarse, non-identifying** dimensions:

| Dimension         | Example         | Source                           |
| ----------------- | --------------- | -------------------------------- |
| `platform`        | `"android"`     | Compile-time constant            |
| `osVersion`       | `"34"`          | `Build.VERSION.SDK_INT`          |
| `deviceClass`     | `"phone"`, `"tablet"` | `Configuration.screenLayout`     |
| `region`          | `"US"`          | `Locale.getDefault()` (country)  |
| `language`        | `"en"`          | `Locale.getDefault()` (language) |
| `appVersion`      | `"2.1.0"`       | `PackageManager`                 |
| `buildNumber`     | `"142"`         | `PackageManager`                 |
| `daysSinceInstall` | `45`            | `PackageManager.firstInstallTime` |
| `sdkVersion`      | `"android/0.2.3"` | SDK platform and version identifier |
| `distributionChannel` | `"play_store"`, `"sideloaded"`, `"debug"` | `PackageManager` installer + build config |

## What Trackless Does NOT Collect

- No GAID (Google Advertising ID) or SSAID (Android ID)
- No IMEI, serial number, or hardware identifiers
- No IP-based geolocation (region comes from system locale settings)
- No persistent storage (no SharedPreferences, files, databases, or any local persistence)
- No cross-session linking of any kind
- No data sent to third parties
- No stack traces, crash logs, or error messages — error tracking uses only developer-defined names, severity levels, and codes
- No individual performance measurements stored — durations are aggregated into statistical digests
- PII auto-stripping of email addresses, phone numbers, and SSN patterns from all event fields
- No Android permissions required

## Google Play Data Safety

When completing the Data Safety section in Google Play Console, declare the following (all **Not Linked to User Identity**, **Not Shared with Third Parties**):

| Category                       | Data Type        | Why                                                                  |
| ------------------------------ | ---------------- | -------------------------------------------------------------------- |
| App activity                   | App interactions | Feature counts, view counts, funnel steps                            |
| App info and performance       | Crash logs       | Error events (name, severity, code — no stack traces)                |
| App info and performance       | Diagnostics      | Performance metrics (duration digest — no individual measurements)   |

## License

MIT License. See [LICENSE](LICENSE) for details.
