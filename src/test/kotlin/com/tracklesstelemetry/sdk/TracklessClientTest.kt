package com.tracklesstelemetry.sdk

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [Trackless] static singleton API.
 */
@DisplayName("Trackless")
class TracklessClientTest {

    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var resources: Resources
    private lateinit var configuration: Configuration
    private lateinit var appInfo: ApplicationInfo
    private lateinit var packageManager: PackageManager
    private lateinit var packageInfo: PackageInfo

    private val testApiKey = "tl_0123456789abcdef0123456789abcdef"
    private val testEndpoint = "https://api.test.com"

    @BeforeEach
    fun setUp() {
        application = mockk(relaxed = true)
        context = mockk(relaxed = true)
        resources = mockk(relaxed = true)
        configuration = Configuration()
        appInfo = ApplicationInfo()
        packageManager = mockk(relaxed = true)
        packageInfo = PackageInfo()

        configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL
        appInfo.flags = 0 // non-debuggable (production)
        packageInfo.versionName = "1.0.0"
        @Suppress("DEPRECATION")
        packageInfo.versionCode = 1
        packageInfo.firstInstallTime = System.currentTimeMillis() - 86400000L // 1 day ago

        every { context.resources } returns resources
        every { resources.configuration } returns configuration
        every { context.applicationInfo } returns appInfo
        every { context.applicationContext } returns application
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.test.app"
        every { packageManager.getPackageInfo("com.test.app", 0) } returns packageInfo
        every { application.applicationInfo } returns appInfo
        every { application.resources } returns resources
        every { application.packageManager } returns packageManager
        every { application.packageName } returns "com.test.app"

        // Mock HttpClient to prevent actual network calls
        mockkObject(HttpClient)
        every { HttpClient.send(any(), any(), any()) } returns SendResult(statusCode = 200)

        // Reset singleton state
        Trackless.resetForTesting()
    }

    @AfterEach
    fun tearDown() {
        Trackless.resetForTesting()
        unmockkAll()
    }

    private fun configure(
        config: TracklessConfig = TracklessConfig(
            apiKey = testApiKey,
            endpoint = testEndpoint,
            flushIntervalSeconds = 999_999L,
        )
    ) {
        Trackless.configure(context, config)
    }

    // ─── Flush sends correct format ─────────────────────────────────────────

    @Test
    @DisplayName("Flush sends correct HTTP request format")
    fun flushSendsCorrectFormat() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("export_clicked")
        Trackless.flush()

        assertTrue(payloadSlot.isCaptured)
        val payload = payloadSlot.captured
        val featureEvents = payload.events.filter { it.type == EventType.FEATURE }
        assertEquals(1, featureEvents.size)
        assertEquals("export_clicked", featureEvents[0].name)
        assertEquals("production", payload.environment)
        assertEquals("android", payload.context.platform)

        verify { HttpClient.send(testEndpoint, testApiKey, any()) }
    }

    // ─── Empty buffer -> no HTTP request ────────────────────────────────────

    @Test
    @DisplayName("Empty buffer does not make HTTP request")
    fun emptyBufferNoHttpRequest() {
        configure()
        // Flush the session:start event first
        Trackless.flush()
        io.mockk.clearMocks(HttpClient, answers = false)

        Trackless.flush()
        verify(exactly = 0) { HttpClient.send(any(), any(), any()) }
    }

    // ─── Environment auto-detection ─────────────────────────────────────────

    @Test
    @DisplayName("Debuggable app auto-detects SANDBOX environment")
    fun debuggableAppAutoDetectsSandbox() {
        appInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("export_clicked")
        Trackless.flush()

        assertTrue(payloadSlot.isCaptured)
        assertEquals("sandbox", payloadSlot.captured.environment)
    }

    @Test
    @DisplayName("Non-debuggable app auto-detects PRODUCTION environment")
    fun nonDebuggableAppAutoDetectsProduction() {
        appInfo.flags = 0
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("export_clicked")
        Trackless.flush()

        assertTrue(payloadSlot.isCaptured)
        assertEquals("production", payloadSlot.captured.environment)
    }

    @Test
    @DisplayName("Explicit environment overrides auto-detection")
    fun explicitEnvironmentOverridesAutoDetection() {
        appInfo.flags = 0
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure(
            TracklessConfig(
                apiKey = testApiKey,
                endpoint = testEndpoint,
                environment = TracklessEnvironment.SANDBOX,
                flushIntervalSeconds = 999_999L,
            )
        )
        Trackless.feature("export_clicked")
        Trackless.flush()

        assertTrue(payloadSlot.isCaptured)
        assertEquals("sandbox", payloadSlot.captured.environment)
    }

    // ─── Invalid names silently ignored ──────────────────────────────────────

    @Test
    @DisplayName("Invalid event names are silently ignored")
    fun invalidNamesAreIgnored() {
        configure()
        // Flush the initial session:start event
        Trackless.flush()
        io.mockk.clearMocks(HttpClient, answers = false)

        Trackless.feature("")
        Trackless.feature("!!!")
        Trackless.feature("@#\$%^&")

        Trackless.flush()

        verify(exactly = 0) { HttpClient.send(any(), any(), any()) }
    }

    @Test
    @DisplayName("Uppercase feature names are normalized to lowercase")
    fun uppercaseNamesNormalized() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("ExportClicked")
        Trackless.flush()

        assertTrue(payloadSlot.isCaptured)
        val featureEvents = payloadSlot.captured.events.filter { it.type == EventType.FEATURE }
        assertEquals("exportclicked", featureEvents[0].name)
    }

    // ─── Disabled mode ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Disabled mode: events are silently ignored")
    fun disabledModeEventsIgnored() {
        configure(
            TracklessConfig(
                apiKey = testApiKey,
                endpoint = testEndpoint,
                enabled = false,
                flushIntervalSeconds = 999_999L,
            )
        )

        Trackless.feature("export_clicked")
        Trackless.flush()

        verify(exactly = 0) { HttpClient.send(any(), any(), any()) }
    }

    // ─── setEnabled ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("setEnabled(false) discards buffered data immediately")
    fun setEnabledFalseDiscardsData() {
        configure()

        Trackless.feature("export_clicked")
        Trackless.setEnabled(false)
        Trackless.flush()

        verify(exactly = 0) { HttpClient.send(any(), any(), any()) }
    }

    @Test
    @DisplayName("setEnabled(true) resumes with empty buffer")
    fun setEnabledTrueResumesWithEmptyBuffer() {
        configure()

        Trackless.feature("export_clicked")
        Trackless.setEnabled(false)
        Trackless.setEnabled(true)

        Trackless.flush()
        verify(exactly = 0) { HttpClient.send(any(), any(), any()) }

        Trackless.feature("new_event")
        Trackless.flush()
        verify(exactly = 1) { HttpClient.send(any(), any(), any()) }
    }

    // ─── onError ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("onError receives flush errors")
    fun onErrorReceivesFlushErrors() {
        every { HttpClient.send(any(), any(), any()) } returns SendResult(statusCode = -1, isNetworkError = true)

        val errors = mutableListOf<Throwable>()
        configure(
            TracklessConfig(
                apiKey = testApiKey,
                endpoint = testEndpoint,
                onError = { errors.add(it) },
                flushIntervalSeconds = 999_999L,
            )
        )

        Trackless.feature("export_clicked")
        Trackless.flush()

        assertEquals(1, errors.size)
        assertTrue(errors[0].message?.contains("network error") == true)
    }

    @Test
    @DisplayName("onError receives validation errors")
    fun onErrorReceivesValidationErrors() {
        val errors = mutableListOf<Throwable>()
        configure(
            TracklessConfig(
                apiKey = testApiKey,
                endpoint = testEndpoint,
                onError = { errors.add(it) },
                flushIntervalSeconds = 999_999L,
            )
        )

        Trackless.feature("!!!")
        assertEquals(1, errors.size)
        assertTrue(errors[0] is IllegalArgumentException)
    }

    // ─── Circuit Breaker ────────────────────────────────────────────────────

    @Test
    @DisplayName("5xx response triggers circuit breaker backoff")
    fun fiveXxTriggersCircuitBreaker() {
        every { HttpClient.send(any(), any(), any()) } returns SendResult(statusCode = 500)

        configure()
        Trackless.feature("export_clicked")
        Trackless.flush()

        Trackless.feature("import_clicked")
        Trackless.flush()

        verify(exactly = 1) { HttpClient.send(any(), any(), any()) }
    }

    @Test
    @DisplayName("4xx response discards batch with NO backoff")
    fun fourXxDiscardsWithNoBackoff() {
        every { HttpClient.send(any(), any(), any()) } returns SendResult(statusCode = 400)

        configure()
        Trackless.feature("export_clicked")
        Trackless.flush()

        every { HttpClient.send(any(), any(), any()) } returns SendResult(statusCode = 200)

        Trackless.feature("import_clicked")
        Trackless.flush()

        verify(exactly = 2) { HttpClient.send(any(), any(), any()) }
    }

    // ─── Typed Events ───────────────────────────────────────────────────────

    @Test
    @DisplayName("View events are recorded correctly")
    fun viewEventsRecorded() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.view("home")
        Trackless.view("home")
        Trackless.flush()

        assertTrue(payloadSlot.isCaptured)
        val viewEvents = payloadSlot.captured.events.filter { it.type == EventType.VIEW }
        assertEquals(1, viewEvents.size)
        assertEquals(2, viewEvents[0].count)
    }

    @Test
    @DisplayName("View events include detail when provided")
    fun viewEventsIncludeDetail() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.view("home", "tab_a")
        Trackless.flush()

        assertTrue(payloadSlot.isCaptured)
        val viewEvents = payloadSlot.captured.events.filter { it.type == EventType.VIEW }
        assertEquals(1, viewEvents.size)
        assertEquals("tab_a", viewEvents[0].detail)
    }

    @Test
    @DisplayName("Feature events include detail when provided")
    fun featureEventsIncludeDetail() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("export", "csv")
        Trackless.flush()

        assertTrue(payloadSlot.isCaptured)
        val featureEvents = payloadSlot.captured.events.filter { it.type == EventType.FEATURE }
        assertEquals(1, featureEvents.size)
        assertEquals("csv", featureEvents[0].detail)
    }

    @Test
    @DisplayName("Error events include severity and code")
    fun errorEventsIncludeSeverityAndCode() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.error("crash", ErrorSeverity.FATAL, "E001")
        Trackless.flush()

        assertTrue(payloadSlot.isCaptured)
        val errorEvents = payloadSlot.captured.events.filter { it.type == EventType.ERROR }
        assertEquals(1, errorEvents.size)
        assertEquals(ErrorSeverity.FATAL, errorEvents[0].severity)
        assertEquals("e001", errorEvents[0].code)
    }

    @Test
    @DisplayName("Performance events include duration")
    fun performanceEventsIncludeDuration() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.performance("api_call", 150.0)
        Trackless.flush()

        assertTrue(payloadSlot.isCaptured)
        val perfEvents = payloadSlot.captured.events.filter { it.type == EventType.PERFORMANCE }
        assertEquals(1, perfEvents.size)
        assertTrue(perfEvents[0].durations?.contains(150.0) == true)
    }

    @Test
    @DisplayName("Funnel events deduplicate and include stepIndex")
    fun funnelEventsDeduplicateAndIncludeStepIndex() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.funnel("checkout", 0, "cart")
        Trackless.funnel("checkout", 1, "payment")
        Trackless.funnel("checkout", 0, "cart") // duplicate — should be ignored

        Trackless.flush()

        assertTrue(payloadSlot.isCaptured)
        val funnelEvents = payloadSlot.captured.events.filter { it.type == EventType.FUNNEL }
        assertEquals(2, funnelEvents.size)
    }

    // ─── 4xx error callback ───────────────────────────────────────────────

    @Test
    @DisplayName("4xx response calls onError with rejection message")
    fun fourXxCallsOnError() {
        every { HttpClient.send(any(), any(), any()) } returns SendResult(statusCode = 400)

        val errors = mutableListOf<Throwable>()
        configure(
            TracklessConfig(
                apiKey = testApiKey,
                endpoint = testEndpoint,
                onError = { errors.add(it) },
                flushIntervalSeconds = 999_999L,
            )
        )

        Trackless.feature("export_clicked")
        Trackless.flush()

        assertTrue(errors.any { it.message?.contains("400") == true })
    }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Lifecycle callbacks are registered on configure")
    fun lifecycleCallbacksRegistered() {
        configure()
        verify { application.registerActivityLifecycleCallbacks(any()) }
    }

    // ─── Never Throws ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Event methods never throw")
    fun eventMethodsNeverThrow() {
        configure()

        Trackless.feature("valid_name")
        Trackless.feature("")
        Trackless.feature("   ")
        Trackless.view("home")
        Trackless.view("home", "tab_a")
        Trackless.feature("export", "csv")
        Trackless.performance("api", 100.0)
        Trackless.error("crash")
    }

    @Test
    @DisplayName("flush() never throws")
    fun flushNeverThrows() {
        every { HttpClient.send(any(), any(), any()) } throws RuntimeException("Unexpected error")

        configure()
        Trackless.feature("export_clicked")
        Trackless.flush()
    }

    @Test
    @DisplayName("destroy() never throws")
    fun destroyNeverThrows() {
        every { HttpClient.send(any(), any(), any()) } throws RuntimeException("Unexpected error")

        configure()
        Trackless.feature("export_clicked")
        Trackless.destroy()
    }

    // ─── Destroy ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Events after destroy are ignored")
    fun eventsAfterDestroyIgnored() {
        configure()
        Trackless.destroy()

        Trackless.feature("export_clicked")
        Trackless.flush()
    }

    // ─── Multiple Events ────────────────────────────────────────────────────

    @Test
    @DisplayName("Multiple feature events aggregate correctly")
    fun multipleEventsAggregate() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.feature("export_clicked")
        Trackless.feature("import_clicked")
        Trackless.feature("export_clicked")
        Trackless.flush()

        assertTrue(payloadSlot.isCaptured)
        val featureEvents = payloadSlot.captured.events.filter { it.type == EventType.FEATURE }
        assertEquals(2, featureEvents.size)

        val exportItem = featureEvents.find { it.name == "export_clicked" }
        val importItem = featureEvents.find { it.name == "import_clicked" }
        assertEquals(2, exportItem?.count)
        assertEquals(1, importItem?.count)
    }
}
