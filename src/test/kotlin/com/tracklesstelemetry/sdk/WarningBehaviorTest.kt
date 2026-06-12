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
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for the one-shot developer warnings: pre-configure event drops
 * and buffer-full drops. Mirrors the iOS WarningBehaviorTests shared spec.
 */
@DisplayName("Warning Behavior")
class WarningBehaviorTest {

    private lateinit var context: Context
    private lateinit var application: Application
    private lateinit var resources: Resources
    private lateinit var configuration: Configuration
    private lateinit var appInfo: ApplicationInfo
    private lateinit var packageManager: PackageManager
    private lateinit var packageInfo: PackageInfo

    private val testApiKey = "tl_0123456789abcdef0123456789abcdef"
    private val testEndpoint = "https://api.test.com"

    private val warnings = mutableListOf<String>()

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

        // Reset singleton state, then attach the warning recorder
        Trackless.resetForTesting()
        warnings.clear()
        Trackless.onWarning = { warnings.add(it) }
    }

    @AfterEach
    fun tearDown() {
        Trackless.resetForTesting()
        unmockkAll()
    }

    private fun configure(suppressWarnings: Boolean = false) {
        Trackless.configure(
            context,
            TracklessConfig(
                apiKey = testApiKey,
                endpoint = testEndpoint,
                flushIntervalSeconds = 999_999L,
                suppressWarnings = suppressWarnings,
            ),
        )
    }

    // ─── Pre-Configure Drops ────────────────────────────────────────────────

    @Test
    @DisplayName("Events recorded before configure() warn once")
    fun preConfigureWarnsOnce() {
        Trackless.feature("early_feature")
        Trackless.view("early_view")
        Trackless.funnel("checkout", 0, "cart")
        Trackless.performance("load", 1.0)
        Trackless.error("boom")

        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("configure"))
    }

    @Test
    @DisplayName("Recording works after configure() with no further pre-configure warnings")
    fun configureEnablesRecording() {
        Trackless.feature("early_feature")
        assertEquals(1, warnings.size)

        configure()
        Trackless.feature("later_feature")

        // Session start + the recorded feature
        assertEquals(2, Trackless.bufferSizeForTesting())
        assertEquals(1, warnings.size)
    }

    // ─── Buffer-Full Drops ──────────────────────────────────────────────────

    @Test
    @DisplayName("Buffer-full drops warn once per session")
    fun bufferFullWarnsOnce() {
        configure()
        Trackless.replaceBufferForTesting(maxItems = 2)

        Trackless.feature("one")
        Trackless.feature("two")
        Trackless.feature("three")
        Trackless.feature("four")

        assertEquals(2, Trackless.bufferSizeForTesting())
        val bufferWarnings = warnings.filter { it.contains("buffer full") }
        assertEquals(1, bufferWarnings.size)
    }

    @Test
    @DisplayName("Buffer-full warning respects suppressWarnings")
    fun bufferFullWarningSuppressed() {
        configure(suppressWarnings = true)
        Trackless.replaceBufferForTesting(maxItems = 1)

        Trackless.feature("one")
        Trackless.feature("two")

        assertEquals(1, Trackless.bufferSizeForTesting())
        assertTrue(warnings.isEmpty())
    }

    @Test
    @DisplayName("Buffer-full warning is re-armed when a new session starts")
    fun bufferFullWarningResetsOnNewSession() {
        configure()
        Trackless.replaceBufferForTesting(maxItems = 1)
        Trackless.feature("one")
        Trackless.feature("two")

        // Destroying and reconfiguring starts a new session, which re-arms the warning.
        Trackless.destroy()
        configure()
        Trackless.replaceBufferForTesting(maxItems = 1)
        Trackless.feature("three")
        Trackless.feature("four")

        val bufferWarnings = warnings.filter { it.contains("buffer full") }
        assertEquals(2, bufferWarnings.size)
    }
}
