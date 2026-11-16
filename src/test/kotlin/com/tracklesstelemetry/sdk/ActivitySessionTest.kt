package com.tracklesstelemetry.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Sessions follow the Activities the SDK has seen start, including when it is
 * registered while one is already on screen and when a rotation replaces one.
 *
 * Before the fix, a counter that started at 0 went to -1 when a screen showing
 * at registration stopped, and the next start only brought it back to 0, so
 * no session began until the count passed 1 again; and a rotation, which
 * stops the old instance before the new one starts, ended the session.
 */
@DisplayName("Sessions and Activity lifecycle")
class ActivitySessionTest {

    private lateinit var context: Context
    private lateinit var application: Application

    private val sent = mutableListOf<EventPayload>()
    private val registered = mutableListOf<Application.ActivityLifecycleCallbacks>()

    @BeforeEach
    fun setUp() {
        application = mockk(relaxed = true)
        context = mockk(relaxed = true)
        val resources: Resources = mockk(relaxed = true)
        val configuration = Configuration()
        val appInfo = ApplicationInfo()
        val packageManager: PackageManager = mockk(relaxed = true)
        val packageInfo = PackageInfo()

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
        every { application.registerActivityLifecycleCallbacks(capture(registered)) } just runs

        mockkObject(HttpClient)
        every { HttpClient.send(any(), any(), any()) } answers {
            synchronized(sent) { sent.add(thirdArg()) }
            SendResult(statusCode = 200)
        }

        Trackless.resetForTesting()
        sent.clear()
        registered.clear()
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
                apiKey = "tl_0123456789abcdef0123456789abcdef",
                endpoint = "https://api.test.com",
                flushIntervalSeconds = 999_999L,
            ),
        )
    }

    private val callbacks: Application.ActivityLifecycleCallbacks
        get() = registered.last()

    private fun activity(rotating: Boolean = false): Activity =
        mockk(relaxed = true) { every { isChangingConfigurations } returns rotating }

    private fun open(a: Activity) {
        callbacks.onActivityStarted(a)
        callbacks.onActivityResumed(a)
    }

    private fun leave(a: Activity) {
        callbacks.onActivityPaused(a)
        callbacks.onActivityStopped(a)
        // The background end and its flush run on the flush thread.
        Trackless.awaitFlushesForTesting()
    }

    /** Session starts and ends sent so far, rolled-up counts included. */
    private fun sessions(): Pair<Int, Int> {
        flushAndWait()
        val events = synchronized(sent) { sent.flatMap { it.events } }
        fun total(name: String) =
            events.filter { it.type == EventType.SESSION && it.name == name }.sumOf { it.count ?: 1 }
        return total("start") to total("end")
    }

    @Test
    @DisplayName("configure() before any Activity: the first screen does not start a second session")
    fun configureBeforeFirstActivity() {
        configure()
        val a = activity()
        open(a)
        leave(a)
        assertEquals(1 to 1, sessions())
    }

    @Test
    @DisplayName("configure() while a screen is showing: background ends it, the return starts a new one")
    fun lateConfigureThenBackgroundAndReturn() {
        val showing = activity()
        configure()
        leave(showing) // never seen to start
        open(showing)
        leave(showing)
        assertEquals(2 to 2, sessions())
    }

    @Test
    @DisplayName("configure() while a screen is showing, then a second screen: one session until both are gone")
    fun lateConfigureThenSecondScreen() {
        val first = activity()
        configure()
        val second = activity()
        open(second)
        leave(first) // the first screen goes behind the second
        assertEquals(1 to 0, sessions())
        leave(second)
        assertEquals(1 to 1, sessions())
    }

    @Test
    @DisplayName("setEnabled(true) from a screen that is showing: sessions still follow the app")
    fun reEnableWhileShowing() {
        configure()
        val settings = activity()
        open(settings)
        Trackless.setEnabled(false)
        Trackless.setEnabled(true) // re-registers while the settings screen shows
        leave(settings)
        open(settings)
        leave(settings)
        // Disabling drops the first session unsent; re-enabling and returning start two.
        assertEquals(2 to 2, sessions())
    }

    @Test
    @DisplayName("A rotation keeps the session open")
    fun rotationKeepsSession() {
        configure()
        val before = activity(rotating = true)
        open(before)
        leave(before)
        val after = activity()
        open(after)
        assertEquals(1 to 0, sessions())
        leave(after)
        assertEquals(1 to 1, sessions())
    }

    @Test
    @DisplayName("Funnel steps on both sides of a session end are all sent")
    fun funnelStepsAcrossSessionEndAreAllSent() {
        configure()
        val a = activity()
        open(a)
        Trackless.funnel("checkout", 0, "cart")
        Trackless.funnel("checkout", 1, "payment")
        leave(a) // ends session 1
        open(a) // starts session 2
        Trackless.funnel("checkout", 0, "cart")
        Trackless.funnel("checkout", 1, "payment")
        flushAndWait()

        val order = synchronized(sent) { sent.flatMap { it.events } }
            .filter { it.type == EventType.FUNNEL || (it.type == EventType.SESSION && it.name != "duration") }
            .map { if (it.type == EventType.FUNNEL) "step${it.stepIndex}" else "session:${it.name}" }
        assertEquals(listOf("session:start", "step0", "step1", "session:end", "session:start", "step0", "step1"), order)
    }
}
