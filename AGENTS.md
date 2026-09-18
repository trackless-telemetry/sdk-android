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
   `Trackless.feature("export", "csv")`, not `Trackless.feature("export_csv")`. Name the feature,
   put the variant in `detail`: the dashboard stores them as separate fields and groups detail
   distributions per name; concatenation destroys that grouping.
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

Trackless.configure(context: Context, config: TracklessConfig)
Trackless.isConfigured: Boolean
Trackless.view(name: String, detail: String? = null)
Trackless.feature(name: String, detail: String? = null)
Trackless.funnel(funnelName: String, stepIndex: Int, stepName: String)
Trackless.performance(name: String, durationSeconds: Double, thresholdSeconds: Double? = null)
Trackless.error(name: String, code: String? = null)
Trackless.info(name: String, detail: String? = null)
Trackless.flush()
Trackless.setEnabled(isEnabled: Boolean)
Trackless.destroy()
```

`TracklessConfig(apiKey: String, endpoint = DEFAULT_ENDPOINT, environment: TracklessEnvironment? =
null, enabled = true, onError: ((Throwable) -> Unit)? = null, flushIntervalSeconds = 60L,
debugLogging = false, suppressWarnings = false)` — only `apiKey` is required.
`TracklessEnvironment`: `SANDBOX`, `PRODUCTION`.

## Errors and info

- `error(name: String, code: String? = null)` — something went wrong. Counts toward errors per
  session and every alert.
- `info(name: String, detail: String? = null)` — something worth counting that the user did not
  do and that did not go wrong (a tier, a unit preference, a theme, a fallback path that fired).
  Never counts toward errors and never triggers an alert.
- Call `info()` **once per session** for a property you want a session split on, right after
  `configure()`: `Trackless.info("tier", if (user.isPaid) "paid" else "free")`. Each value's
  count then equals the sessions that reported it. It counts **sessions, not people** — one
  person across four sessions is four. Report configuration many sessions share, never anything
  about the person.
- **Never share a name between `error()` and `info()`.** One store, two levels, and the
  session-reach marker dedups on the name alone.
- `error(name, severity, code)` still compiles: the `severity` parameter is deprecated, not
  removed. `ErrorSeverity.ERROR`, `.WARNING` and `.FATAL` are sent as `error`; `.INFO` and
  `.DEBUG` as `info`. Write `error(name, code)` and `info(name, detail)` in new code.

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
- App version and build number are auto-read from `PackageManager` (the app's own
  `versionName` / `versionCode`). The SDK sends no distribution channel, and reads no install
  date and no install source — never add `firstInstallTime`, `getInstallSourceInfo` or
  `getInstallerPackageName`.
- Sessions are managed automatically via `ActivityLifecycleCallbacks` — no manual handling.
- For Compose view tracking, use `LaunchedEffect` on `currentBackStackEntryAsState()` (GUIDE.md).
- All event methods are non-blocking, thread-safe, and never throw.
- No ProGuard/R8 configuration needed — the SDK uses no reflection or runtime class lookup, so nothing needs keeping. It ships no consumer rules file because none is required.
- No persistent identifiers of any kind — never add GAID/SSAID or any device ID to any path.

## Verify

Configure with `debugLogging = true`, record one event, then call `Trackless.flush()` **off the
main thread** (it performs network I/O on the calling thread). Watch logcat
(`adb logcat -s Trackless`) for `flush success — status=200` — with `configured — env=...`,
`feature — ...`, and `flush — 1 events` confirming the earlier steps; failures log
`flush failed/rejected — status=...`. GUIDE.md §12 carries the full recipe and §13 the
troubleshooting decoder (400/401/402/413/429/5xx). When the first event lands, the
dashboard's getting-started checklist marks **"See your first feature data"**.

## After release: the loop back to you

Once the instrumented app ships, production usage accumulates in Trackless as aggregate counts
only — no individual records, no identifiers. From the dashboard's Agent pack page, the
developer can copy or download a pack — the counts for a chosen window and slice,
together with instructions for reading them — and paste it into the agent they already use
(likely you). Trackless itself never calls a model and never analyzes anything; interpreting the
counts against the codebase is the customer's agent's job. Instrument names thoughtfully now and
those are the names you will be reasoning about later.
