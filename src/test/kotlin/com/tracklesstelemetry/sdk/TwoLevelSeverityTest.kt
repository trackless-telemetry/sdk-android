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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Coverage for the two stored levels: `error(name, code?)` and
 * `info(name, detail?)`.
 *
 * The SDK maps whatever severity a caller passes to one of two values before the
 * event is buffered, so nothing but `error` or `info` reaches the wire. These
 * tests pin that mapping, the wire shape `info()` produces, and the fact that
 * `info()` behaves like every other non-session event (depth, session reach,
 * normalization, PII stripping).
 */
@DisplayName("Trackless — two stored levels")
class TwoLevelSeverityTest {

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
        appInfo.flags = 0
        packageInfo.versionName = "1.0.0"
        @Suppress("DEPRECATION")
        packageInfo.versionCode = 1

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

        mockkObject(HttpClient)
        every { HttpClient.send(any(), any(), any()) } returns SendResult(statusCode = 200)

        Trackless.resetForTesting()
    }

    @AfterEach
    fun tearDown() {
        Trackless.resetForTesting()
        unmockkAll()
    }

    private fun configure() {
        Trackless.configure(
            context,
            TracklessConfig(
                apiKey = testApiKey,
                endpoint = testEndpoint,
                flushIntervalSeconds = 999_999L,
            ),
        )
    }

    /** A severity an already-installed app may still send, by raw value. */
    private fun sent(raw: String): ErrorSeverity = ErrorSeverity.entries.first { it.value == raw }

    // ─── Client-side mapping ────────────────────────────────────────────────

    @Test
    @DisplayName("Every legacy severity maps to one of the two stored levels")
    fun mapsFiveToTwo() {
        assertEquals(ErrorSeverity.INFO, Trackless.storedSeverity(sent("debug")))
        assertEquals(ErrorSeverity.INFO, Trackless.storedSeverity(sent("info")))
        assertEquals(ErrorSeverity.ERROR, Trackless.storedSeverity(sent("warning")))
        assertEquals(ErrorSeverity.ERROR, Trackless.storedSeverity(sent("error")))
        assertEquals(ErrorSeverity.ERROR, Trackless.storedSeverity(sent("fatal")))
    }

    @Test
    @DisplayName("The five severity values still exist for already-installed callers")
    fun allFiveStillExist() {
        assertEquals(
            listOf("debug", "info", "warning", "error", "fatal"),
            ErrorSeverity.entries.map { it.value },
        )
    }

    @Test
    @DisplayName("Each legacy severity is sent at its stored level, never as passed")
    @Suppress("DEPRECATION") // exercises the legacy severity parameter on purpose
    fun sendsStoredLevelOnly() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.error("e_debug", ErrorSeverity.DEBUG)
        Trackless.error("e_info", ErrorSeverity.INFO)
        Trackless.error("e_warning", ErrorSeverity.WARNING)
        Trackless.error("e_error", ErrorSeverity.ERROR)
        Trackless.error("e_fatal", ErrorSeverity.FATAL)
        Trackless.flush()

        val byName = payloadSlot.captured.events
            .filter { it.type == EventType.ERROR }
            .associate { it.name to it.severity }
        assertEquals(ErrorSeverity.INFO, byName["e_debug"])
        assertEquals(ErrorSeverity.INFO, byName["e_info"])
        assertEquals(ErrorSeverity.ERROR, byName["e_warning"])
        assertEquals(ErrorSeverity.ERROR, byName["e_error"])
        assertEquals(ErrorSeverity.ERROR, byName["e_fatal"])
    }

    @Test
    @DisplayName("Mixed legacy severities for one name and code roll up to one entry")
    @Suppress("DEPRECATION") // exercises the legacy severity parameter on purpose
    fun mixedSeveritiesRollUp() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.error("payment_failed", ErrorSeverity.FATAL, "DECLINED")
        Trackless.error("payment_failed", ErrorSeverity.WARNING, "DECLINED")
        Trackless.error("payment_failed", ErrorSeverity.ERROR, "DECLINED")
        Trackless.flush()

        val errors = payloadSlot.captured.events.filter { it.type == EventType.ERROR }
        assertEquals(1, errors.size)
        assertEquals(ErrorSeverity.ERROR, errors[0].severity)
        assertEquals("declined", errors[0].code)
        assertEquals(3, errors[0].count)
        assertEquals(1, errors[0].firstOccurrences)
    }

    // ─── info() ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("info() sends the detail as code at severity info")
    fun infoWireShape() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.info("tier", "paid")
        Trackless.flush()

        val event = payloadSlot.captured.events.first { it.type == EventType.ERROR }
        assertEquals("tier", event.name)
        assertEquals(ErrorSeverity.INFO, event.severity)
        assertEquals("paid", event.code)

        val json = event.toJson()
        assertEquals("error", json.getString("type"))
        assertEquals("tier", json.getString("name"))
        assertEquals("info", json.getString("severity"))
        assertEquals("paid", json.getString("code"))
    }

    @Test
    @DisplayName("info() without a detail carries no code")
    fun infoWithoutDetail() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.info("offline_fallback")
        Trackless.flush()

        val event = payloadSlot.captured.events.first { it.type == EventType.ERROR }
        assertEquals(ErrorSeverity.INFO, event.severity)
        assertNull(event.code)
        assertTrue(!event.toJson().has("code"))
    }

    @Test
    @DisplayName("An error and an info on one name are two rows sharing one first occurrence")
    fun sharedNameSharesReach() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.error("tier")
        Trackless.info("tier", "paid")
        Trackless.flush()

        val rows = payloadSlot.captured.events.filter { it.type == EventType.ERROR && it.name == "tier" }
        assertEquals(2, rows.size)
        assertEquals(1, rows.first { it.severity == ErrorSeverity.ERROR }.firstOccurrences)
        assertNull(rows.first { it.severity == ErrorSeverity.INFO }.firstOccurrences)
    }

    @Test
    @DisplayName("info() marks the first occurrence once per session")
    fun infoFirstOccurrenceOncePerSession() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.info("tier", "paid")
        Trackless.info("tier", "paid")
        Trackless.flush()

        val row = payloadSlot.captured.events.first { it.type == EventType.ERROR && it.name == "tier" }
        assertEquals(2, row.count)
        assertEquals(1, row.firstOccurrences)
    }

    @Test
    @DisplayName("info() increments session depth like any other non-session event")
    fun infoIncrementsSessionDepth() {
        val payloads = mutableListOf<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloads)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.info("tier", "paid")
        Trackless.info("units", "metric")
        Trackless.info("tier", "paid")
        Trackless.destroy() // ends the session and flushes

        val endEvent = payloads.flatMap { it.events }
            .find { it.type == EventType.SESSION && it.name == "end" }
        // `stepIndex` on a session-end event carries the session's depth.
        assertEquals(3, endEvent?.stepIndex)
    }

    @Test
    @DisplayName("The info detail is normalized and PII-stripped exactly like a code")
    fun infoDetailNormalized() {
        val payloadSlot = slot<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloadSlot)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.info("tier", "Paid Tier")
        Trackless.info("contact", "user@example.com")
        Trackless.flush()

        val byName = payloadSlot.captured.events
            .filter { it.type == EventType.ERROR }
            .associate { it.name to it.code }
        assertEquals("paid_tier", byName["tier"])
        assertEquals("redacted", byName["contact"])
    }

    @Test
    @DisplayName("A rejected info name records nothing")
    fun rejectedInfoNameRecordsNothing() {
        val payloads = mutableListOf<EventPayload>()
        every { HttpClient.send(any(), any(), capture(payloads)) } returns SendResult(statusCode = 200)

        configure()
        Trackless.info("!!!", "paid") // normalizes to empty — the same guard error() applies
        Trackless.flush()

        assertTrue(payloads.flatMap { it.events }.none { it.type == EventType.ERROR })
    }
}
