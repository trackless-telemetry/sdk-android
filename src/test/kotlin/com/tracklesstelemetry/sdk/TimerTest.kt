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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `startTimer` / `stopTimer` / `cancelTimer` and `measure`. Mirrors the Swift
 * TimerTests and the web timer tests.
 */
@DisplayName("Timers")
class TimerTest {

    private lateinit var context: Context
    private lateinit var application: Application

    private val testApiKey = "tl_0123456789abcdef0123456789abcdef"
    private val otherApiKey = "tl_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    private val warnings = mutableListOf<String>()
    private val errors = mutableListOf<Throwable>()
    private val sent = mutableListOf<EventPayload>()

    /** Hand-driven millisecond clock. */
    private var nowMs = 1_000_000L

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

        mockkObject(HttpClient)
        every { HttpClient.send(any(), any(), any()) } answers {
            synchronized(sent) { sent.add(thirdArg()) }
            SendResult(statusCode = 200)
        }

        Trackless.resetForTesting()
        warnings.clear()
        errors.clear()
        sent.clear()
        nowMs = 1_000_000L
        Trackless.onWarning = { warnings.add(it) }
        Trackless.replaceTimerClockForTesting { nowMs }
    }

    @AfterEach
    fun tearDown() {
        Trackless.resetForTesting()
        unmockkAll()
    }

    private fun configure(apiKey: String = testApiKey) {
        Trackless.configure(
            context,
            TracklessConfig(
                apiKey = apiKey,
                endpoint = "https://api.test.com",
                onError = { errors.add(it) },
                flushIntervalSeconds = 999_999L,
            ),
        )
    }

    private fun advance(seconds: Double) {
        nowMs += (seconds * 1000).toLong()
    }

    private fun sentPerformance(): List<TracklessEvent> {
        flushAndWait()
        return synchronized(sent) { sent.flatMap { it.events } }.filter { it.type == EventType.PERFORMANCE }
    }

    // ─── Start and stop ──────────────────────────────────────────────────────

    @Test
    @DisplayName("stopTimer sends the elapsed time and threshold as a performance measurement")
    fun stopSendsPerformance() {
        configure()
        Trackless.startTimer("checkout")
        advance(1.5)
        Trackless.stopTimer("checkout", thresholdSeconds = 2.0)

        val events = sentPerformance()
        assertEquals(1, events.size)
        assertEquals("checkout", events[0].name)
        assertEquals(listOf(1.5), events[0].durations)
        assertEquals(2.0, events[0].threshold)
    }

    @Test
    @DisplayName("The name is normalized at start, so a differently written stop closes it")
    fun normalizedNameMatches() {
        configure()
        Trackless.startTimer("Checkout Flow")
        advance(3.0)
        Trackless.stopTimer("checkout_flow")

        val events = sentPerformance()
        assertEquals(listOf("checkout_flow"), events.map { it.name })
        assertEquals(listOf(3.0), events[0].durations)
        assertEquals(0, Trackless.openTimerCountForTesting())
    }

    @Test
    @DisplayName("stopTimer with no open timer sends nothing")
    fun stopWithoutStartIsNoOp() {
        configure()
        Trackless.stopTimer("never_started")
        Trackless.startTimer("once")
        Trackless.stopTimer("once")
        Trackless.stopTimer("once")

        val events = sentPerformance()
        assertEquals(1, events.size)
        assertEquals(1, events[0].durations?.size)
    }

    @Test
    @DisplayName("cancelTimer drops the timer and sends nothing")
    fun cancelSendsNothing() {
        configure()
        Trackless.startTimer("upload")
        advance(2.0)
        Trackless.cancelTimer("Upload")
        Trackless.stopTimer("upload")

        assertTrue(sentPerformance().isEmpty())
    }

    @Test
    @DisplayName("Starting an open name again restarts it")
    fun restartResetsStart() {
        configure()
        Trackless.startTimer("search")
        advance(10.0)
        Trackless.startTimer("search")
        advance(4.0)
        Trackless.stopTimer("search")

        assertEquals(listOf(4.0), sentPerformance()[0].durations)
    }

    // ─── Cap ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("At most 50 timers are open; a start past the cap is ignored with one warning")
    fun capIgnoresAndWarnsOnce() {
        configure()
        repeat(OpenTimers.MAX_OPEN) { Trackless.startTimer("timer_$it") }
        Trackless.startTimer("one_too_many")
        Trackless.startTimer("two_too_many")

        assertEquals(OpenTimers.MAX_OPEN, Trackless.openTimerCountForTesting())
        assertEquals(1, warnings.count { it.contains("timers are already open") })

        // Restarting an open name is not a new timer.
        Trackless.startTimer("timer_0")
        assertEquals(OpenTimers.MAX_OPEN, Trackless.openTimerCountForTesting())

        Trackless.stopTimer("one_too_many")
        assertTrue(sentPerformance().isEmpty())
    }

    @Test
    @DisplayName("The cap warning re-arms after the session ends")
    fun capWarningRearmsPerSession() {
        configure()
        repeat(OpenTimers.MAX_OPEN + 1) { Trackless.startTimer("timer_$it") }
        Trackless.setEnabled(false)
        Trackless.setEnabled(true)
        repeat(OpenTimers.MAX_OPEN + 1) { Trackless.startTimer("timer_$it") }

        assertEquals(2, warnings.count { it.contains("timers are already open") })
    }

    // ─── Session end ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("setEnabled(false) discards open timers")
    fun disableDiscards() {
        configure()
        Trackless.startTimer("checkout")
        Trackless.setEnabled(false)
        Trackless.setEnabled(true)
        advance(1.0)
        Trackless.stopTimer("checkout")

        assertTrue(sentPerformance().isEmpty())
    }

    @Test
    @DisplayName("A reconfigure with changed settings discards open timers; identical settings keep them")
    fun reconfigureDiscardsOnlyOnChange() {
        configure()
        Trackless.startTimer("kept")
        configure()
        assertEquals(1, Trackless.openTimerCountForTesting())

        configure(apiKey = otherApiKey)
        assertEquals(0, Trackless.openTimerCountForTesting())
        Trackless.stopTimer("kept")
        assertTrue(sentPerformance().isEmpty())
    }

    @Test
    @DisplayName("destroy() discards open timers")
    fun destroyDiscards() {
        configure()
        Trackless.startTimer("checkout")
        destroyAndWait()
        assertEquals(0, Trackless.openTimerCountForTesting())
    }

    @Test
    @DisplayName("A timer started before the first configure survives it")
    fun startBeforeFirstConfigureSurvives() {
        Trackless.startTimer("launch")
        configure()
        advance(0.75)
        Trackless.stopTimer("launch")

        assertEquals(listOf(0.75), sentPerformance()[0].durations)
    }

    // ─── Elapsed bounds ──────────────────────────────────────────────────────

    @Test
    @DisplayName("An elapsed time over a day is dropped with a warning; exactly a day is kept")
    fun overADayDropped() {
        configure()
        Trackless.startTimer("too_long")
        advance(OpenTimers.MAX_ELAPSED_SECONDS + 1)
        Trackless.stopTimer("too_long")
        assertTrue(warnings.any { it.contains("too_long") && it.contains("outside") })

        Trackless.startTimer("a_day")
        advance(OpenTimers.MAX_ELAPSED_SECONDS)
        Trackless.stopTimer("a_day")

        val events = sentPerformance()
        assertEquals(listOf("a_day"), events.map { it.name })
        assertEquals(listOf(OpenTimers.MAX_ELAPSED_SECONDS), events[0].durations)
    }

    @Test
    @DisplayName("A negative elapsed time is dropped")
    fun negativeElapsedDropped() {
        configure()
        Trackless.startTimer("checkout")
        nowMs -= 5_000
        Trackless.stopTimer("checkout")

        assertTrue(sentPerformance().isEmpty())
    }

    // ─── Invalid names ───────────────────────────────────────────────────────

    @Test
    @DisplayName("A name normalization rejects is ignored with the usual warning and onError")
    fun invalidNameRejected() {
        configure()
        Trackless.startTimer("日本語")
        Trackless.stopTimer("日本語")
        Trackless.cancelTimer("日本語")

        assertEquals(0, Trackless.openTimerCountForTesting())
        assertEquals(1, warnings.count { it.contains("event name rejected") })
        assertEquals(1, errors.size)
        assertTrue(sentPerformance().isEmpty())
    }

    @Test
    @DisplayName("A timer stopped while the SDK is disabled sends nothing")
    fun stopWhileDisabledSendsNothing() {
        configure()
        Trackless.setEnabled(false)
        Trackless.startTimer("checkout")
        advance(1.0)
        Trackless.stopTimer("checkout")
        assertEquals(0, Trackless.bufferSizeForTesting())
    }

    // ─── measure ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("measure returns the block's value and sends its duration")
    fun measureSends() {
        configure()
        val value = Trackless.measure("Parse CSV", thresholdSeconds = 0.5) {
            advance(0.25)
            42
        }

        assertEquals(42, value)
        val events = sentPerformance()
        assertEquals("parse_csv", events[0].name)
        assertEquals(listOf(0.25), events[0].durations)
        assertEquals(0.5, events[0].threshold)
        assertEquals(0, Trackless.openTimerCountForTesting())
    }

    @Test
    @DisplayName("measure sends nothing when the block throws, and the exception propagates")
    fun measureThrowSendsNothing() {
        configure()
        assertThrows(IllegalStateException::class.java) {
            Trackless.measure("parse") { throw IllegalStateException("boom") }
        }
        assertTrue(sentPerformance().isEmpty())
    }

    @Test
    @DisplayName("measure sends nothing when the session ends while the block runs")
    fun measureAcrossSessionEndSendsNothing() {
        configure()
        Trackless.measure("feed_load") {
            Trackless.setEnabled(false)
            Trackless.setEnabled(true)
            advance(1.0)
        }
        assertTrue(sentPerformance().isEmpty())
    }

    @Test
    @DisplayName("measure does not count toward the open-timer cap")
    fun measureIgnoresCap() {
        configure()
        repeat(OpenTimers.MAX_OPEN) { Trackless.startTimer("timer_$it") }
        Trackless.measure("nested") { advance(1.0) }

        assertEquals(listOf("nested"), sentPerformance().map { it.name })
        assertFalse(warnings.any { it.contains("timers are already open") })
    }
}
