# Trackless Android SDK — Implementation Guide

> This guide is designed for AI coding assistants. Follow the steps exactly to add privacy-first analytics to any Android application.

## 1. Install

### Gradle (Kotlin DSL)

Add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.tracklesstelemetry:sdk-android:0.2.4")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.tracklesstelemetry:sdk-android:0.2.4'
}
```

**Requirements:** Android API 24+ (Android 7.0), Java 17, Kotlin 1.9+. Zero external dependencies.

## 2. Configure

Call `Trackless.configure()` once at app launch — before any events are recorded.

### Application Class (Recommended)

```kotlin
import android.app.Application
import com.tracklesstelemetry.sdk.Trackless
import com.tracklesstelemetry.sdk.TracklessConfig

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Trackless.configure(
            context = this,
            config = TracklessConfig(
                apiKey = "tl_your_api_key_here"
            )
        )
    }
}
```

Don't forget to register the Application class in `AndroidManifest.xml`:

```xml
<application
    android:name=".MyApp"
    ... >
```

### Activity (Alternative)

If you don't have a custom Application class:

```kotlin
import com.tracklesstelemetry.sdk.Trackless
import com.tracklesstelemetry.sdk.TracklessConfig

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Trackless.configure(
            context = applicationContext,
            config = TracklessConfig(
                apiKey = "tl_your_api_key_here"
            )
        )
    }
}
```

### Configuration Options

```kotlin
TracklessConfig(
    apiKey = "tl_your_api_key_here",       // Required — API key with tl_ prefix
    endpoint = "https://custom.api.com",   // Optional — defaults to https://api.tracklesstelemetry.com
    environment = TracklessEnvironment.SANDBOX,  // Optional — auto-detected from build
    enabled = true,                         // Optional — set false to disable all recording
    onError = { error -> Log.w("Trackless", error) },  // Optional — error callback
    flushIntervalSeconds = 60L,            // Optional — flush interval in seconds
    debugLogging = true,                   // Optional — enable debug logging for happy-path events
    suppressWarnings = false,              // Optional — suppress warning and error logging
)
```

| Option                 | Type                       | Default                                | Description                                        |
| ---------------------- | -------------------------- | -------------------------------------- | -------------------------------------------------- |
| `apiKey`               | `String`                   | **required**                           | API key with `tl_` prefix                          |
| `endpoint`             | `String`                   | `"https://api.tracklesstelemetry.com"` | Ingest endpoint URL                                |
| `environment`          | `TracklessEnvironment?`    | auto-detected                          | `SANDBOX` or `PRODUCTION`                          |
| `enabled`              | `Boolean`                  | `true`                                 | Set `false` to disable all recording               |
| `onError`              | `((Throwable) -> Unit)?`   | `null`                                 | Error callback for debugging                       |
| `flushIntervalSeconds` | `Long`                     | `60`                                   | Flush interval in seconds                          |
| `debugLogging`         | `Boolean`                  | `false`                                | Enable debug logging for happy-path events         |
| `suppressWarnings`     | `Boolean`                  | `false`                                | Suppress warning and error logging                 |

**Environment auto-detection:** If the app has `FLAG_DEBUGGABLE` set, environment defaults to `SANDBOX`. Otherwise `PRODUCTION`. Override by passing `environment` explicitly.

**App version auto-detection:** `appVersion` and `buildNumber` are automatically read from `PackageManager`.

## 3. Track Events

All methods are static. Call them anywhere after `configure()`. Every method is non-blocking, thread-safe, and never throws.

### Views

Record when a user views a screen, with an optional detail:

```kotlin
Trackless.view("home")
Trackless.view("settings")
Trackless.view("profile.edit")
Trackless.view("settings", "notifications")  // with detail
```

**When to use:** Activity/Fragment appearances, tab switches, Compose navigation destinations.

**Jetpack Compose — NavHost:**

```kotlin
import com.tracklesstelemetry.sdk.Trackless

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    // Track screen changes
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentBackStackEntry) {
        currentBackStackEntry?.destination?.route?.let { route ->
            Trackless.view(route)
        }
    }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") { HomeScreen() }
        composable("settings") { SettingsScreen() }
        composable("profile") { ProfileScreen() }
    }
}
```

**Activity-based:**

```kotlin
class SettingsActivity : AppCompatActivity() {
    override fun onResume() {
        super.onResume()
        Trackless.view("settings")
    }
}
```

**Fragment-based:**

```kotlin
class ProfileFragment : Fragment() {
    override fun onResume() {
        super.onResume()
        Trackless.view("profile")
    }
}
```

### Feature Usage

Record when a user interacts with a feature, with an optional detail:

```kotlin
Trackless.feature("export_clicked")
Trackless.feature("dark_mode_toggled")
Trackless.feature("photo-upload")
Trackless.feature("settings", "notifications")  // with detail
Trackless.feature("export", "csv")               // with detail
Trackless.feature("theme", "dark")               // with detail
```

**When to use:** Button clicks, toggle switches, menu selections, user-initiated actions.

**Compose button example:**

```kotlin
Button(onClick = {
    Trackless.feature("export_data")
    exportData()
}) {
    Text("Export Data")
}
```

**XML layout click handler:**

```kotlin
binding.exportButton.setOnClickListener {
    Trackless.feature("export_data")
    exportData()
}
```

### Funnel Steps

Track progression through multi-step flows. Each step has a developer-defined index (0-based) that determines its position in the funnel:

```kotlin
// Checkout funnel
Trackless.funnel("checkout", 0, "view_cart")
Trackless.funnel("checkout", 1, "enter_shipping")
Trackless.funnel("checkout", 2, "enter_payment")
Trackless.funnel("checkout", 3, "confirm_order")
Trackless.funnel("checkout", 4, "order_complete")

// Onboarding funnel
Trackless.funnel("onboarding", 0, "welcome")
Trackless.funnel("onboarding", 1, "create_account")
Trackless.funnel("onboarding", 2, "permissions")
Trackless.funnel("onboarding", 3, "complete")
```

**When to use:** Checkout flows, onboarding wizards, multi-step forms — any process where you want to measure drop-off between steps.

**Rules:**
- Step index is developer-defined (0-based) and determines the order of steps in funnel charts
- Steps are deduplicated per session — calling the same step index twice is a no-op
- Funnel state resets when the session ends

### Performance Metrics

Record timing measurements in seconds, with an optional **threshold** for breach tracking:

```kotlin
// Measure API call duration
val startNanos = System.nanoTime()
val data = apiClient.fetchUserProfile()
Trackless.performance(
    "api_user_profile",
    durationSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
)

// Measure database query
val startNanos = System.nanoTime()
val results = database.query(sql)
Trackless.performance(
    "db_query_products",
    durationSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
)

// App startup time (measure in Application.onCreate)
Trackless.performance("app_cold_start", durationSeconds = coldStartDurationSeconds)

// With threshold — track how many measurements exceed 2 seconds
Trackless.performance(
    "api_user_profile",
    durationSeconds = elapsed,
    thresholdSeconds = 2.0
)
```

**When to use:** API latency, database queries, image processing, app startup — any timing you want percentile distributions for (p50/p90/p99).

**Threshold:** The optional `thresholdSeconds` parameter defines a performance threshold. Each name/threshold combination is tracked separately, with breach counts shown in the dashboard.

**Important:** Duration is in **seconds** (not milliseconds or nanoseconds). Divide `System.nanoTime()` differences by `1_000_000_000.0`. Threshold must be > 0.

### Errors

Record application errors with severity and optional code:

```kotlin
// Basic error
Trackless.error("payment_failed", severity = ErrorSeverity.ERROR)

// With error code
Trackless.error("api_timeout", severity = ErrorSeverity.WARNING, code = "ETIMEDOUT")
Trackless.error("validation_failed", severity = ErrorSeverity.INFO, code = "INVALID_EMAIL")

// In a catch block
try {
    submitOrder()
} catch (e: Exception) {
    Trackless.error(
        "order_submission",
        severity = ErrorSeverity.ERROR,
        code = e.javaClass.simpleName
    )
}
```

**Severity levels:** `ErrorSeverity.DEBUG`, `.INFO`, `.WARNING`, `.ERROR`, `.FATAL`

**When to use:** Caught exceptions, failed network requests, validation errors, any error you want to trend over time.

## 4. Event Naming Rules

All event fields (`name`, `detail`, `step`, `code`) are automatically normalized before buffering:

| Rule | Detail |
|------|--------|
| **Auto-lowercase** | Fields are lowercased — `Export_Clicked` becomes `export_clicked` |
| **Auto-normalize** | Spaces and invalid characters are replaced with `_` — `Sign Up Button` becomes `sign_up_button` |
| **Trim** | Leading/trailing underscores and dots are removed — `...foo...` becomes `foo` |
| **Collapse dots** | Consecutive dots are collapsed — `foo..bar` becomes `foo.bar` |
| **Truncate** | Truncated to 100 characters |
| **No identifiers** | UUIDs, long hex strings, and numeric-only strings >12 chars are rejected |
| **PII stripping** | Emails, phone numbers, and SSN patterns are stripped from all fields |

**Valid characters after normalization:** Lowercase `a-z`, digits `0-9`, underscores `_`, hyphens `-`, dots `.`

**Examples:** `"Sign Up Button"` → `"sign_up_button"`, `"ERR_001"` → `"err_001"`, `"Export!Clicked"` → `"export_clicked"`, `"Settings.Theme"` → `"settings.theme"`

### Feature Grouping with Detail

Use the optional `detail` parameter to distinguish variants within a feature. The dashboard groups features that have detail values and shows donut charts with the distribution.

```kotlin
// These create a "theme" group in the dashboard with "dark" and "light" values
Trackless.feature("theme", "dark")
Trackless.feature("theme", "light")

// Use detail for any choice-from-a-set scenario
Trackless.feature("distance_preset", "1_mile")
Trackless.feature("distance_preset", "2_miles")
Trackless.feature("settings", "notifications")
```

**Which types support grouping?** The `detail` parameter is supported on `feature` and `view` events. The dashboard's automatic group visualization (donut charts) applies to both.

## 5. Session Lifecycle

Sessions are managed automatically via `ActivityLifecycleCallbacks`. No code needed.

- **Start:** A session begins when `Trackless.configure()` is called, and a new session starts each time the app returns to the foreground
- **End:** A session ends when all Activities leave the foreground — the session-end event (with duration and depth) is flushed immediately
- **Depth:** Every non-session event increments the session's depth counter
- **Duration:** Measured from session start to session end
- **Context:** `daysSinceInstall` is computed from `PackageManager.firstInstallTime`

## 6. Flush Behavior

Events are buffered in memory and sent in batches:

- **Periodic flush:** Every 60 seconds if the buffer is non-empty
- **Item threshold:** When the buffer reaches 100 unique items
- **Session end:** Flushed when the app goes to background
- **Manual:** Call `Trackless.flush()` at any time
- **Client-side rollup:** Duplicate events are pre-aggregated (e.g., 50 `feature("save")` calls become one event with `count: 50`)
- **Circuit breaker:** Server errors trigger exponential backoff (30s → 60s → 5m → 15m → 60m)

## 7. Runtime Controls

```kotlin
// Check if the SDK is configured (useful in shared/library code)
if (Trackless.isConfigured) {
    Trackless.feature("shared_action")
}

// Disable recording (e.g., user opts out)
Trackless.setEnabled(false)   // Discards buffer, stops timers

// Re-enable recording
Trackless.setEnabled(true)    // Resumes from empty buffer

// Force flush
Trackless.flush()

// Permanent shutdown
Trackless.destroy()           // Flushes remaining events, then disables permanently
```

## 8. Complete Integration Example

### Compose App with All Event Types

```kotlin
// MyApp.kt
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Trackless.configure(
            context = this,
            config = TracklessConfig(
                apiKey = BuildConfig.TRACKLESS_API_KEY
            )
        )
    }
}
```

```kotlin
// AppNavigation.kt
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()

    LaunchedEffect(currentEntry) {
        currentEntry?.destination?.route?.let { Trackless.view(it) }
    }

    NavHost(navController, startDestination = "home") {
        composable("home") { HomeScreen(navController) }
        composable("search") { SearchScreen() }
        composable("cart") { CartScreen(navController) }
        composable("checkout") { CheckoutScreen(navController) }
        composable("settings") { SettingsScreen() }
    }
}
```

```kotlin
// SearchScreen.kt
@Composable
fun SearchScreen() {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Product>()) }
    val scope = rememberCoroutineScope()

    Column {
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search...") }
        )

        Button(onClick = {
            Trackless.feature("search_executed")
            scope.launch {
                val startNanos = System.nanoTime()
                try {
                    results = searchProducts(query)
                    Trackless.performance(
                        "search_api",
                        durationSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
                    )
                } catch (e: Exception) {
                    Trackless.error("search_failed", severity = ErrorSeverity.ERROR,
                        code = e.javaClass.simpleName)
                }
            }
        }) {
            Text("Search")
        }

        LazyColumn {
            items(results) { product -> ProductCard(product) }
        }
    }
}
```

```kotlin
// CheckoutScreen.kt
@Composable
fun CheckoutScreen(navController: NavController) {
    var step by remember { mutableStateOf(CheckoutStep.CART) }
    val scope = rememberCoroutineScope()

    when (step) {
        CheckoutStep.CART -> {
            Trackless.funnel("checkout", 0, "view_cart")
            CartSummary(onContinue = { step = CheckoutStep.SHIPPING })
        }
        CheckoutStep.SHIPPING -> {
            ShippingForm(onSelect = { method ->
                Trackless.feature("shipping_method", method)
                Trackless.funnel("checkout", 1, "enter_shipping")
                step = CheckoutStep.PAYMENT
            })
        }
        CheckoutStep.PAYMENT -> {
            PaymentForm(onSubmit = {
                Trackless.funnel("checkout", 2, "enter_payment")
                scope.launch {
                    val startNanos = System.nanoTime()
                    try {
                        placeOrder()
                        Trackless.performance(
                            "order_submission",
                            durationSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
                        )
                        Trackless.funnel("checkout", 3, "order_complete")
                        step = CheckoutStep.CONFIRMATION
                    } catch (e: Exception) {
                        Trackless.error("order_failed", severity = ErrorSeverity.ERROR,
                            code = e.javaClass.simpleName)
                    }
                }
            })
        }
        CheckoutStep.CONFIRMATION -> {
            OrderConfirmation()
        }
    }
}
```

```kotlin
// SettingsScreen.kt
@Composable
fun SettingsScreen() {
    var selectedTheme by remember { mutableStateOf("system") }

    Column {
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        listOf("system", "light", "dark").forEach { theme ->
            Row(
                modifier = Modifier.clickable {
                    selectedTheme = theme
                    Trackless.feature("theme", theme)
                }
            ) {
                RadioButton(selected = selectedTheme == theme, onClick = null)
                Text(theme.replaceFirstChar { it.uppercase() })
            }
        }

        Button(onClick = {
            Trackless.feature("data_export")
            exportData()
        }) {
            Text("Export Data")
        }

        Button(onClick = {
            Trackless.feature("clear_cache")
            clearCache()
        }) {
            Text("Clear Cache")
        }
    }
}
```

## 9. Privacy Guarantees

Trackless collects **no user identifiers** and stores **only aggregate counts**:

- **No GAID (Google Advertising ID) or SSAID**
- **No device serial number, IMEI, or hardware identifiers**
- **No IP address processing by application code** — IP addresses are never read, parsed, stored, or used by the SDK or the Trackless backend. Region comes from system locale, not IP geolocation. (AWS infrastructure receives IP addresses for network routing and DDoS protection as part of standard cloud operations, but they are not used for analytics.)
- **No persistent storage** — no SharedPreferences, files, databases, or any local persistence
- **No cross-session linking** — session state is in-memory only
- **No data sent to third parties** — events go only to your configured endpoint
- **No stack traces, crash logs, or error messages** — error tracking uses only developer-defined names, severity levels, and codes
- **No individual performance measurements stored** — durations are aggregated server-side into statistical digests (t-digest)
- **PII auto-stripping** — email addresses, phone numbers, and SSN patterns are automatically stripped from all event fields before buffering
- **No permissions required** — the SDK requires no Android permissions

The only context collected is: platform (`"android"`), OS version (API level integer, e.g., `"34"`), device class (phone/tablet from screen size), region (two-letter country code from `Locale.getDefault()`, e.g., `"US"`), language (ISO 639-1 code from `Locale.getDefault().language`, e.g., `"en"`), app version, build number, days since install, and `sdkVersion` (automatically included, e.g., `"android/0.2.4"`), and distribution channel (automatically detected: `"play_store"`, `"galaxy_store"`, `"amazon_store"`, `"sideloaded"`, `"debug"`, or `"unknown"`). All are coarse, non-identifying dimensions.

### Google Play Data Safety

When completing the Data Safety section in Google Play Console, declare the following (all **Not Linked to User Identity**, **Not Shared with Third Parties**):

| Category | Data Type | Why |
|----------|-----------|-----|
| App activity | App interactions | Feature counts, view counts, funnel steps |
| App info and performance | Crash logs | Error events (name, severity, code — no stack traces) |
| App info and performance | Diagnostics | Performance metrics (duration digest — no individual measurements) |

See [Section 22.7 of the SDK requirements](https://github.com/trackless-telemetry/platform/blob/main/docs/requirements/sdks.md#227-app-store-privacy-compliance-guidance) for full guidance.

## 10. API Key Management

Store the API key securely. Do **not** hardcode it in source files committed to version control.

**Recommended: BuildConfig fields from `local.properties`:**

```properties
# local.properties (already in .gitignore)
TRACKLESS_API_KEY=tl_your_api_key_here
```

```kotlin
// build.gradle.kts (app module)
android {
    val localProps = java.util.Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }

    defaultConfig {
        buildConfigField("String", "TRACKLESS_API_KEY",
            "\"${localProps.getProperty("TRACKLESS_API_KEY", "")}\"")
    }
}
```

```kotlin
// Usage
Trackless.configure(
    context = this,
    config = TracklessConfig(
        apiKey = BuildConfig.TRACKLESS_API_KEY
    )
)
```

## 11. ProGuard / R8

If you use code shrinking, the SDK ships its own ProGuard rules. No additional configuration needed.
