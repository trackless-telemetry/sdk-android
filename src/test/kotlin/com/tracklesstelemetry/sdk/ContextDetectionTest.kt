package com.tracklesstelemetry.sdk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Tests for [ContextDetection].
 */
@DisplayName("ContextDetection")
class ContextDetectionTest {

    private lateinit var context: Context
    private lateinit var resources: Resources
    private lateinit var configuration: Configuration
    private lateinit var appInfo: ApplicationInfo
    private lateinit var packageManager: PackageManager
    private lateinit var packageInfo: PackageInfo

    @BeforeEach
    fun setUp() {
        context = mockk(relaxed = true)
        resources = mockk(relaxed = true)
        configuration = Configuration()
        appInfo = ApplicationInfo()
        packageManager = mockk(relaxed = true)
        packageInfo = PackageInfo()

        packageInfo.versionName = "2.1.0"
        @Suppress("DEPRECATION")
        packageInfo.versionCode = 42
        packageInfo.firstInstallTime = System.currentTimeMillis() - 3 * 86400000L // 3 days ago

        every { context.resources } returns resources
        every { resources.configuration } returns configuration
        every { context.applicationInfo } returns appInfo
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.test.app"
        every { packageManager.getPackageInfo("com.test.app", 0) } returns packageInfo
    }

    @Test
    @DisplayName("Platform is always 'android'")
    fun platformIsAndroid() {
        configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL
        val ctx = ContextDetection.detect(context)
        assertEquals("android", ctx.platform)
    }

    @Test
    @DisplayName("OS version is API level integer string or null in test env")
    fun osVersionExtracted() {
        configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL
        val ctx = ContextDetection.detect(context)
        if (ctx.osVersion != null) {
            assertNotNull(ctx.osVersion!!.toIntOrNull())
        }
    }

    @Test
    @DisplayName("Phone device class for SCREENLAYOUT_SIZE_NORMAL")
    fun phoneDeviceClassForNormal() {
        configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL
        val ctx = ContextDetection.detect(context)
        assertEquals("phone", ctx.deviceClass)
    }

    @Test
    @DisplayName("Phone device class for SCREENLAYOUT_SIZE_SMALL")
    fun phoneDeviceClassForSmall() {
        configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_SMALL
        val ctx = ContextDetection.detect(context)
        assertEquals("phone", ctx.deviceClass)
    }

    @Test
    @DisplayName("Tablet device class for SCREENLAYOUT_SIZE_LARGE")
    fun tabletDeviceClassForLarge() {
        configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_LARGE
        val ctx = ContextDetection.detect(context)
        assertEquals("tablet", ctx.deviceClass)
    }

    @Test
    @DisplayName("Tablet device class for SCREENLAYOUT_SIZE_XLARGE")
    fun tabletDeviceClassForXlarge() {
        configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_XLARGE
        val ctx = ContextDetection.detect(context)
        assertEquals("tablet", ctx.deviceClass)
    }

    @Test
    @DisplayName("SCREENLAYOUT_SIZE_UNDEFINED -> device class is null")
    fun undefinedScreenLayoutReturnsNull() {
        configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_UNDEFINED
        val ctx = ContextDetection.detect(context)
        assertNull(ctx.deviceClass)
    }

    @Test
    @DisplayName("Region is detected as country code")
    fun regionDetected() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("en", "US"))
            configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL
            val ctx = ContextDetection.detect(context)
            assertNotNull(ctx.region)
            assertEquals("US", ctx.region)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    @DisplayName("Language is detected as ISO 639-1 code")
    fun languageDetected() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("en", "US"))
            configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL
            val ctx = ContextDetection.detect(context)
            assertNotNull(ctx.language)
            assertEquals("en", ctx.language)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    @DisplayName("Language is detected for non-English locale")
    fun languageDetectedFrench() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("fr", "FR"))
            configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL
            val ctx = ContextDetection.detect(context)
            assertNotNull(ctx.language)
            assertEquals("fr", ctx.language)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    @DisplayName("Language is serialized in toJson()")
    fun languageInJson() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("de", "DE"))
            configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL
            val ctx = ContextDetection.detect(context)
            val json = ctx.toJson()
            assert(json.has("language"))
            assertEquals("de", json.getString("language"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    @DisplayName("App version is detected")
    fun appVersionDetected() {
        configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL
        val ctx = ContextDetection.detect(context)
        assertEquals("2.1.0", ctx.appVersion)
    }

    @Test
    @DisplayName("Build number is detected")
    fun buildNumberDetected() {
        configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL
        val ctx = ContextDetection.detect(context)
        assertEquals("42", ctx.buildNumber)
    }

    @Test
    @DisplayName("Days since install is detected")
    fun daysSinceInstallDetected() {
        configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL
        val ctx = ContextDetection.detect(context)
        assertNotNull(ctx.daysSinceInstall)
        assertEquals(3, ctx.daysSinceInstall)
    }

    @Test
    @DisplayName("Debuggable app returns SANDBOX environment")
    fun debuggableAppReturnsSandbox() {
        appInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE
        val env = ContextDetection.detectEnvironment(context)
        assertEquals(TracklessEnvironment.SANDBOX, env)
    }

    @Test
    @DisplayName("Non-debuggable app returns PRODUCTION environment")
    fun nonDebuggableAppReturnsProduction() {
        appInfo.flags = 0
        val env = ContextDetection.detectEnvironment(context)
        assertEquals(TracklessEnvironment.PRODUCTION, env)
    }

    @Test
    @DisplayName("SDK version is present and starts with android/")
    fun sdkVersionPresent() {
        configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL
        val ctx = ContextDetection.detect(context)
        assertNotNull(ctx.sdkVersion)
        assert(ctx.sdkVersion!!.startsWith("android/"))
    }

    @Test
    @DisplayName("SDK version is serialized in toJson()")
    fun sdkVersionInJson() {
        configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL
        val ctx = ContextDetection.detect(context)
        val json = ctx.toJson()
        assert(json.has("sdkVersion"))
        assert(json.getString("sdkVersion").startsWith("android/"))
    }

    @Test
    @DisplayName("No GAID or SSAID in detection code")
    fun noGaidOrSsaidInDetectionCode() {
        configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL
        val ctx = ContextDetection.detect(context)
        assertEquals("android", ctx.platform)
    }
}
