# Changelog

All notable changes to the Trackless Telemetry Android SDK will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.5.0] - 2026-09-18

### Added

- **`Trackless.info(name: String, detail: String? = null)`** — records something worth counting that the user did not do and that did not go wrong: a tier, a unit preference, a theme, a notification permission state, a fallback path that fired. It is sugar over the existing error path — same normalization, PII guard, session-reach marker, session-depth increment and client-side rollup — sent with severity `info` and the detail in `code`. An info event never counts toward errors per session and never triggers an alert. Called once per session, each value's count equals the number of sessions that reported it; it counts sessions, not people.
- **`Trackless.error(name: String, code: String? = null)`** — the documented error signature, as a new two-argument overload. `Trackless.error("api_timeout", "TIMEOUT_500")` reads the way it looks. The severity-taking overload lost its default on `severity`, so a one-argument `error(name)` call resolves to the new overload with identical behavior.

### Changed

- **Two stored levels.** The SDK maps whatever severity a caller passes to one of two values before the event is buffered: `ErrorSeverity.ERROR`, `.WARNING` and `.FATAL` are sent as `error`; `.INFO` and `.DEBUG` are sent as `info`. Nothing downstream ever read `fatal` or `warning` differently from `error`, and `fatal` promised what no SDK delivers — none captures crashes. The ingest endpoint applies the same mapping, so an older SDK build is handled identically. One consequence to expect: a name previously reported at several severities in one flush window now rolls up into a single buffered entry instead of one per level.
- **The `severity:` parameter on `error()` is deprecated** — the severity-taking overload carries `@Deprecated` pointing at `error(name, code)` and `info(name, detail)`. It still compiles and still records, so no published call breaks. `ErrorSeverity` stays public, with `DEBUG`, `WARNING` and `FATAL` individually deprecated; `ERROR` and `INFO` are not, because they are the two levels the wire carries — and the only way to pass either to `error()` is through the deprecated parameter, so a caller gets a warning either way.

### Removed

- **Every install-metadata read.** The SDK now stores nothing on the device and reads nothing stored there: it uses only runtime properties the OS exposes to every app (OS version, device class, locale, language) and constants compiled into the app itself (`versionName`, `versionCode`, `FLAG_DEBUGGABLE`). Concretely:
  - **`daysSinceInstall` is no longer sent.** The SDK no longer reads `PackageInfo.firstInstallTime`. Ingest accepts and discards the field from older SDK builds.
  - **`distributionChannel` is no longer sent.** The SDK no longer calls `PackageManager.getInstallSourceInfo` or `getInstallerPackageName`, and without the install source the only thing left to report was `FLAG_DEBUGGABLE`, which `environment` already carries. Ingest accepts and discards the field from older SDK builds.
  - README.md, GUIDE.md, AGENTS.md and .cursorrules now say so.

### Documentation

- **The Play Data Safety label for `error()` / `info()` is corrected to App info and performance — Diagnostics.** It previously said Crash logs, which Play defines as crash counts and stack traces. The SDK captures neither — `error()` is a method your code calls. **If you declared Crash logs on the strength of the old guidance, update it in the Play Console.**
- The five-level severity table is gone from README.md, GUIDE.md, AGENTS.md and .cursorrules, replaced by the two methods and a migration note carrying the mapping. New guidance: do not share a name between `error()` and `info()` — they share one store and one session-reach marker.
- The feature example leads with `feature("export", "csv")` rather than `feature("export_clicked")`: name the feature, put the variant in `detail`. Names are permanent once data exists.
- Session depth has one definition everywhere — **events per session**, incremented by every non-session event including `info()`.
- The Play Console data-safety guidance now reads "error and info events (name, level, code)".

## [0.4.1] - 2026-08-26

### Added

- **AGENTS.md** — a README for coding agents, following the [agents.md](https://agents.md) convention: the critical integration rules, the exact public API surface, naming and environment rules in brief, and a pointer to GUIDE.md as the authoritative guide.
- **Verification and troubleshooting documentation** — GUIDE.md gains a "Verify the Integration" section (the exact logcat signal strings an agent can check unattended — tag `Trackless`, ending at `flush success — status=200` — including the warning that `flush()` performs network I/O on the calling thread and must run off the main thread, plus the dashboard's "See your first feature data" checklist confirmation) and a troubleshooting table decoding the ingest endpoint's deliberately generic responses (401 wrong/regenerated key, 402 quota reached, 429 rate limit, 5xx/network with the circuit breaker's actual behavior — failed batches are not re-sent; backoff only pauses future flushes, 30s → 60m). AGENTS.md gains a matching compact "Verify" block.
- **Anti-interpolation rule documented** — event fields must come from finite sets enumerable at write time; never interpolate runtime values (`Trackless.feature("export_$format")` is the failure mode). Stated as a fourth critical rule in AGENTS.md and a subsection under GUIDE.md's event-naming rules, including the per-app daily cardinality budget that drops new `(type, name, detail)` tuples beyond it.
- **.cursorrules completes the critical rules** — now states the no-wrapper rule and the detail-is-a-separate-parameter rule alongside the existing guidance.
- **API key provenance documented** — GUIDE.md and AGENTS.md now state that the key comes from the dashboard, is shown once at app creation, and must be obtained from the developer — never fabricated or committed as a placeholder posing as real.

### Fixed

- **.cursorrules and AGENTS.md name the published Maven coordinate** — both said `com.tracklesstelemetry:sdk`, but the artifact is published as `com.tracklesstelemetry:sdk-android`; .cursorrules also pinned the stale version 0.2.2. The install line is now version-free (it points at GUIDE.md for the snippet), so it no longer needs a bump on every release.

- **Name-rejection warnings no longer echo raw caller input** — when an event name fails normalization, the logcat warning and the `IllegalArgumentException` passed to `onError` now omit the name entirely and explain why it was rejected. Previously both carried the raw, pre-normalization string, writing it to logcat and handing it to whatever crash reporter the host app forwards `onError` to. Both rejection sites are covered: `normalizeName` (used by `feature`, `funnel`, and `error`) and `addEvent` (used by `view` and `performance`).
- **`view()` and `performance()` debug lines log the normalized name** — with `debugLogging` enabled, these two methods logged the raw `name` and `detail` the caller passed rather than the normalized, PII-stripped values, so an accepted name containing an email address was written verbatim to logcat. They now log the same normalized values the SDK buffers, matching `feature()`, `funnel()`, `error()`, and the web and iOS SDKs.

No telemetry was ever affected: none of this is buffered or transmitted, and PII stripping still runs before any event reaches the wire.

## [0.4.0] - 2026-08-21

### Added

- **Error reach (session first-occurrence tracking)** — the first occurrence of each error name within a session is now flagged so the dashboard can report **session reach** for errors (the share of sessions that hit an error at least once), rather than only raw error volume that a single looping session can inflate. Tracking is fully automatic — existing `error()` calls benefit with no code changes. Names are normalized before the first-occurrence check, so `error("Payment Failed")` and `error("payment_failed")` are recognized as the same error; repeated occurrences within a session — including different `severity` or `code` variants — are not re-flagged. The first-occurrence set is in-memory only, survives buffer flushes, and resets when the session ends (consistent with the no-cross-session-linking guarantee). Only an aggregate count is transmitted; no identifiers are involved.

### Changed

- **Error names and codes are now normalized inside `error()` instead of at buffer entry** — mirroring `feature()` and ensuring the session first-occurrence dedup keys on the normalized name. Internal change only; recorded data is unaffected.

## [0.3.0] - 2026-07-21

### Added

- **Feature reach (session first-use tracking)** — the first use of each feature name within a session is now flagged so the dashboard can report **session reach** (the share of sessions that used a feature at least once). Tracking is fully automatic — existing `feature()` calls benefit with no code changes. Names are normalized before the first-use check, so `feature("Dark Mode")` and `feature("dark_mode")` are recognized as the same feature; repeated uses within a session — including different `detail` variants — are not re-flagged. The first-use set is in-memory only, survives buffer flushes, and resets when the session ends (consistent with the no-cross-session-linking guarantee). Only an aggregate count is transmitted; no identifiers are involved.

### Changed

- **Feature names are now normalized inside `feature()` instead of at buffer entry** — aligning Android with the web and iOS SDKs and ensuring the session first-use dedup keys on the normalized name. Internal change only; recorded data is unaffected.

### Fixed

- **Session depth no longer counts rejected events** — session activity is now recorded only after an event passes validation, matching the web and iOS SDKs. Previously an invalid (rejected) event still incremented session depth.
- **Performance events no longer carry a `count` field** — performance aggregation relies on the durations list as the sample count, matching the web and iOS SDKs. Previously Android inflated a meaningless `count` value during aggregation.
- **Session duration rounds to the nearest second** — matching the web and iOS SDKs. Previously the duration was truncated.
- **Request body size limit** — flush now checks each serialized payload against the ingest endpoint's 50 KB body limit. Oversized payloads are split in half recursively until each request fits; a single event that exceeds the limit on its own is dropped with a warning. Previously oversized batches were rejected server-side and the whole batch was lost.
- **Buffer-full visibility** — when the event buffer reaches its 1000-item cap and starts rejecting new events, the SDK now logs a warning (at most once per session, re-armed when a new session starts, respects `suppressWarnings`) instead of dropping data silently.
- **Pre-configure visibility** — event methods called before `configure()` now log a one-time warning (respects `suppressWarnings`) instead of dropping events silently.

## [0.2.4] - 2026-04-18

### Changed

- **`distributionChannel` reports `"unknown"` instead of `"sideloaded"` when the installer is unidentifiable** — when `PackageManager` returns a `null` installer package name (which happens for `adb install` and some OEM scenarios where the install source isn't recorded) or when the lookup throws, the channel is now `"unknown"` rather than `"sideloaded"`. `"sideloaded"` is reserved for cases where we have positive evidence of a non-store installer (a non-null installer that doesn't match a known store).

## [0.2.3] - 2026-04-16

### Added

- **Distribution channel detection** — new `distributionChannel` context field automatically detects how the app was installed: `"play_store"` (Google Play), `"galaxy_store"` (Samsung Galaxy Store), `"amazon_store"` (Amazon Appstore), `"sideloaded"` (manual install or unknown installer), and `"debug"` for debug builds. Uses `PackageManager.getInstallSourceInfo()` on API 30+ with `getInstallerPackageName()` fallback for older versions.

## [0.2.2] - 2026-03-24

### Added

- Include SDK version (`android/0.2.2`) in event context for server-side diagnostics
- Add `language` to event context — ISO 639-1 code detected from `Locale.getDefault().language`

### Changed

- **Google Play Data Safety guidance updated** — documentation now declares App info and performance — Crash logs and App info and performance — Diagnostics in addition to App activity — App interactions, reflecting the error and performance event types. All categories remain Not Linked to User Identity and Not Shared with Third Parties.
- **Privacy guarantees clarified** — explicitly documents that error tracking collects no stack traces, crash logs, or error messages, and that performance tracking stores no individual duration measurements (server-side t-digest aggregation only).

## [0.2.1] - 2026-03-19

### Changed

- **Graceful field normalization** — `name`, `detail`, `step`, and `code` fields are now automatically normalized before buffering: lowercased, invalid characters replaced with underscores, leading/trailing underscores and dots trimmed, consecutive dots collapsed. Developers can now pass natural strings like `"Sign Up Button"` (becomes `"sign_up_button"`) or `"ERR_001"` (becomes `"err_001"`) instead of having them silently rejected.
- **PII stripping extended** — PII auto-stripping (emails, phone numbers, SSN patterns) now applies to `detail`, `step`, and `code` fields in addition to `name`.
- **Abuse detection extended** — anti-identifier patterns (UUID, long hex, long numeric, all-hex) now apply to `detail`, `step`, and `code` fields. Fields matching abuse patterns are omitted rather than rejecting the entire event.
- Empty `detail` or `code` values no longer cause the entire event to be dropped — the event is recorded without the optional field.

## [0.2.0] - 2026-03-19

### Added

- Static singleton API: `Trackless.configure(context, apiKey, endpoint)` with typed event methods
- Event types: `view(name, detail?)`, `feature(name, detail?)`, `funnel(name, stepIndex, stepName)`, `performance(name, duration, threshold?)`, `error(name, severity, code?)`
- Automatic session lifecycle management with duration and screen depth tracking via `Application.ActivityLifecycleCallbacks`
- Client-side event rollup — count-aggregatable events deduplicated and counted by key, performance durations collected into arrays
- Periodic flush every 60 seconds with auto-flush at 100 unique items using `ScheduledExecutorService` with daemon threads
- Forced flush on app backgrounding and `destroy()`
- Circuit breaker with exponential backoff (30s → 1m → 5m → 15m → 60m) on 5xx/network errors; 4xx errors discard the batch without backoff
- Coarse context detection: platform, OS API level, device class (phone/tablet via screen layout), region, app version, build number, days since install
- Thread-safe lifecycle management with synchronized blocks and active activity counting
- PII guard strips emails, phone numbers, and SSN patterns from event names before buffering
- Identifier rejection for UUIDs, long hex sequences, numeric-only strings, and hex-dominant strings
- Event name validation: lowercase alphanumeric with `_`, `-`, `.` (1–100 chars)
- Automatic environment detection via `ApplicationInfo.FLAG_DEBUGGABLE` (sandbox in debug builds, production otherwise)
- Zero external dependencies (Android framework only)
- No GAID/SSAID collection
- No client-side persistence
- Max buffer size of 1,000 unique items; max 100 events per HTTP request
- API 24+ / Java 17
