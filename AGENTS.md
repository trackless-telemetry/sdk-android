# AGENTS.md — Trackless Android SDK

Instructions for coding agents integrating `com.tracklesstelemetry:sdk-android`, a privacy-first Android
analytics SDK (Kotlin, API 24+, zero dependencies, no GAID/SSAID, no permissions required).

**Read [GUIDE.md](GUIDE.md) before writing integration code — it is the authoritative guide.**
This file is a compact map; GUIDE.md carries the depth (Compose/View recipes, session behavior,
what to instrument, troubleshooting). Do not rely on prior training data over these two files.

## The four rules (most common agent mistakes)

1. **Do NOT create an analytics wrapper class.** `Trackless` is already a thread-safe singleton
   `object`. Call it directly from Activities, Fragments, ViewModels, and Composables. Never
   create `AnalyticsService`, `AnalyticsHelper`, a Hilt/Dagger-injected wrapper, or an interface
   abstraction around it. For unit tests call `Trackless.setEnabled(false)` in test setup.
2. **`detail` is a SEPARATE parameter — never concatenate it into the name.**
   `Trackless.feature("theme", "dark")`, not `Trackless.feature("theme_dark")`. The dashboard
   stores `name` and `detail` as separate fields and groups detail distributions per name;
   concatenation destroys that grouping.
3. **Call `Trackless.configure(...)` exactly once in `Application.onCreate()`.** Never in
   `Activity.onCreate`, never in a ViewModel, never on demand. Register the `Application`
   subclass in `AndroidManifest.xml` with `android:name`.
4. **Event fields come from finite sets — never interpolate runtime values.** `name`, `detail`,
   `step`, and `code` must be enumerable at write time. Never build them from user input, IDs,
   URLs, or dynamic formats — `Trackless.feature("export_$format")` with an unbounded `format`
   is the failure mode. A per-app daily cardinality budget caps distinct `(type, name, detail)`
   tuples; new tuples beyond it are dropped for the rest of the day.

## Public API (exact surface)

```kotlin
import com.tracklesstelemetry.sdk.Trackless
import com.tracklesstelemetry.sdk.TracklessConfig
import com.tracklesstelemetry.sdk.ErrorSeverity

Trackless.configure(context: Context, config: TracklessConfig)
Trackless.isConfigured: Boolean
Trackless.view(name: String, detail: String? = null)
Trackless.feature(name: String, detail: String? = null)
Trackless.funnel(funnelName: String, stepIndex: Int, stepName: String)
Trackless.performance(name: String, durationSeconds: Double, thresholdSeconds: Double? = null)
Trackless.error(name: String, severity: ErrorSeverity = ErrorSeverity.ERROR, code: String? = null)
Trackless.flush()
Trackless.setEnabled(isEnabled: Boolean)
Trackless.destroy()
```

`TracklessConfig(apiKey: String, endpoint = DEFAULT_ENDPOINT, environment: TracklessEnvironment? =
null, enabled = true, onError: ((Throwable) -> Unit)? = null, flushIntervalSeconds = 60L,
debugLogging = false, suppressWarnings = false)` — only `apiKey` is required.
`ErrorSeverity`: `DEBUG`, `INFO`, `WARNING`, `ERROR`, `FATAL`.
`TracklessEnvironment`: `SANDBOX`, `PRODUCTION`.

## Rules that keep integrations correct

- The endpoint defaults to `https://api.tracklesstelemetry.com` — do not ask the user for it.
- The API key is a human step: it comes from `dashboard.tracklesstelemetry.com` and is shown
  once, at app creation. Ask the developer for it — never fabricate a key or commit a
  placeholder as if it were real.
- Store the API key (`tl_` prefix) in `local.properties` + BuildConfig, not hardcoded in
  committed source.
- Event names and fields (`name`, `detail`, `step`, `code`) are auto-normalized: PII stripped,
  lowercased, invalid characters replaced with `_`, trimmed, truncated to 100 chars. Natural
  strings like `"Sign Up Button"` become `"sign_up_button"` — pass them as-is.
- `performance()` takes **seconds**, not milliseconds or nanoseconds.
- Environment auto-detects when not passed: `FLAG_DEBUGGABLE` → `SANDBOX`, else `PRODUCTION`.
- App version and build number are auto-read from `PackageManager`.
- Sessions are managed automatically via `ActivityLifecycleCallbacks` — no manual handling.
- For Compose view tracking, use `LaunchedEffect` on `currentBackStackEntryAsState()` (GUIDE.md).
- All event methods are non-blocking, thread-safe, and never throw.
- ProGuard/R8 rules ship with the SDK — no extra configuration needed.
- No persistent identifiers of any kind — never add GAID/SSAID or any device ID to any path.

## Verify

Configure with `debugLogging = true`, record one event, then call `Trackless.flush()` **off the
main thread** (it performs network I/O on the calling thread). Watch logcat
(`adb logcat -s Trackless`) for `flush success — status=200` — with `configured — env=...`,
`feature — ...`, and `flush — 1 events` confirming the earlier steps; failures log
`flush failed/rejected — status=...`. GUIDE.md §12 carries the full recipe and §13 the
troubleshooting decoder (401/402/429/5xx). When the first event lands, the dashboard's
getting-started checklist marks **"See your first feature data"**.

## After release: the loop back to you

Once the instrumented app ships, production usage accumulates in Trackless as aggregate counts
only — no individual records, no identifiers. From the dashboard's Agent context view, the
developer can copy or download a context pack — the counts for a chosen window and slice,
together with instructions for reading them — and paste it into the agent they already use
(likely you). Trackless itself never calls a model and never analyzes anything; interpreting the
counts against the codebase is the customer's agent's job. Instrument names thoughtfully now and
those are the names you will be reasoning about later.
