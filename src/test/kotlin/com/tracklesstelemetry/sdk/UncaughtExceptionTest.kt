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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `captureUncaught`: the chained default uncaught-exception handler. Mirrors
 * the Swift UncaughtExceptionTests and the web uncaught tests.
 *
 * The handler is installed into a [FakeSlot] rather than the JVM's own, and a
 * test throws nothing: it calls the installed handler with a constructed
 * throwable, as the runtime would. The previous handler is a recorder.
 */
@DisplayName("Uncaught exceptions")
class UncaughtExceptionTest {

    private lateinit var context: Context
    private lateinit var application: Application

    private val testApiKey = "tl_0123456789abcdef0123456789abcdef"
    private val otherApiKey = "tl_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    private val warnings = mutableListOf<String>()
    private val sent = mutableListOf<EventPayload>()
    private val sentBodies = mutableListOf<String>()
    private val registered = mutableListOf<Application.ActivityLifecycleCallbacks>()

    private lateinit var slot: FakeSlot
    private lateinit var previous: RecordingHandler

    /** A slot standing in for `Thread.setDefaultUncaughtExceptionHandler`. */
    class FakeSlot(override var handler: Thread.UncaughtExceptionHandler?) : DefaultHandlerSlot

    /** The handler that was there before the SDK's: records each call. */
    class RecordingHandler : Thread.UncaughtExceptionHandler {
        val calls = mutableListOf<Pair<Thread, Throwable>>()
        override fun uncaughtException(t: Thread, e: Throwable) {
            synchronized(calls) { calls.add(t to e) }
        }
    }

    /** A class whose name normalization rejects: 32 hex letters in a row. */
    class Abcdefabcdefabcdefabcdefabcdefab : RuntimeException()

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
            record(thirdArg())
            SendResult(statusCode = 200)
        }

        Trackless.resetForTesting()
        warnings.clear()
        sent.clear()
        sentBodies.clear()
        registered.clear()
        Trackless.onWarning = { warnings.add(it) }
        previous = RecordingHandler()
        slot = FakeSlot(previous)
        Trackless.replaceUncaughtCaptureForTesting(slot)
    }

    @AfterEach
    fun tearDown() {
        Trackless.resetForTesting()
        unmockkAll()
    }

    private fun record(payload: EventPayload) {
        synchronized(sent) {
            sent.add(payload)
            sentBodies.add(payload.toJsonString())
        }
    }

    private fun configure(
        apiKey: String = testApiKey,
        enabled: Boolean = true,
        captureUncaught: Boolean = true,
    ) {
        Trackless.configure(
            context,
            TracklessConfig(
                apiKey = apiKey,
                endpoint = "https://api.test.com",
                enabled = enabled,
                flushIntervalSeconds = 999_999L,
                captureUncaught = captureUncaught,
            ),
        )
    }

    /** Hand [throwable] to whatever is installed in the slot, as the runtime would. */
    private fun crash(throwable: Throwable, thread: Thread = Thread.currentThread()) {
        val handler = slot.handler
        assertNotNull(handler)
        handler!!.uncaughtException(thread, throwable)
    }

    private fun sentEvents(): List<TracklessEvent> {
        Trackless.awaitFlushesForTesting()
        return synchronized(sent) { sent.flatMap { it.events } }
    }

    private fun uncaughtEvents(): List<TracklessEvent> =
        sentEvents().filter { it.type == EventType.ERROR && it.name == Trackless.UNCAUGHT_ERROR }

    // ─── Installation ────────────────────────────────────────────────────────

    @Test
    @DisplayName("captureUncaught off: no handler is installed")
    fun offInstallsNothing() {
        configure(captureUncaught = false)
        assertSame(previous, slot.handler)
        assertFalse(Trackless.uncaughtHandlerInstalledForTesting)
    }

    @Test
    @DisplayName("Installed once per process, chained in front of the previous handler")
    fun installedOnce() {
        configure()
        val installed = slot.handler
        assertTrue(installed !== previous)

        configure(apiKey = otherApiKey)
        Trackless.setEnabled(false)
        Trackless.setEnabled(true)
        destroyAndWait()
        configure()
        assertSame(installed, slot.handler)

        crash(IllegalStateException())
        assertEquals(1, previous.calls.size)
    }

    @Test
    @DisplayName("configure(enabled = false) installs nothing until recording is enabled")
    fun installedOnlyWhenEnabled() {
        configure(enabled = false)
        assertSame(previous, slot.handler)
        Trackless.setEnabled(true)
        assertTrue(Trackless.uncaughtHandlerInstalledForTesting)
    }

    // ─── What is sent ────────────────────────────────────────────────────────

    @Test
    @DisplayName("An uncaught exception is sent as trackless.uncaught with the normalized class name, plus the session end")
    fun sendsErrorAndSessionEnd() {
        configure()
        Trackless.feature("export")
        val throwable = IllegalStateException("contact jane.doe@example.com about order 4471")
        val worker = Thread({}, "worker-for-jane")
        crash(throwable, worker)

        assertEquals(listOf(worker to throwable), previous.calls.map { it.first to it.second })

        val events = sentEvents()
        val uncaught = events.single { it.type == EventType.ERROR }
        assertEquals(Trackless.UNCAUGHT_ERROR, uncaught.name)
        assertEquals("java.lang.illegalstateexception", uncaught.code)
        assertEquals(ErrorSeverity.ERROR, uncaught.severity)
        assertEquals(1, uncaught.firstOccurrences)

        val end = events.single { it.type == EventType.SESSION && it.name == "end" }
        // The feature counted; the uncaught exception did not add to the depth.
        assertEquals(1, end.stepIndex)
        // The rest of the buffer went with it.
        assertTrue(events.any { it.type == EventType.FEATURE && it.name == "export" })
        assertEquals(0, Trackless.bufferSizeForTesting())
    }

    @Test
    @DisplayName("Nothing from the message, stack trace, cause or thread reaches the payload")
    fun nothingButTheClassName() {
        configure()
        val cause = IllegalArgumentException("cause-secret-text")
        val throwable = RuntimeException("message-secret-text", cause)
        crash(throwable, Thread({}, "thread-secret-name"))
        Trackless.awaitFlushesForTesting()

        val body = synchronized(sent) { sentBodies.joinToString("\n") }.lowercase()
        assertTrue(body.contains("java.lang.runtimeexception"))
        for (secret in listOf(
            "secret",
            "illegalargumentexception",
            "uncaughtexceptiontest",
            "junit",
            "thread-",
        )) {
            assertFalse(body.contains(secret), "payload contains \"$secret\"")
        }
    }

    @Test
    @DisplayName("A class name normalization rejects is sent as unknown")
    fun rejectedClassNameIsUnknown() {
        configure()
        crash(Abcdefabcdefabcdefabcdefabcdefab())
        assertEquals(listOf(Trackless.UNCAUGHT_UNKNOWN_CODE), uncaughtEvents().map { it.code })
    }

    @Test
    @DisplayName("Nested class names keep their package and lose the dollar sign")
    fun nestedClassName() {
        configure()
        crash(NestedFailure())
        assertEquals(
            listOf("com.tracklesstelemetry.sdk.uncaughtexceptiontest_nestedfailure"),
            uncaughtEvents().map { it.code },
        )
    }

    class NestedFailure : RuntimeException()

    @Test
    @DisplayName("The SDK's own send does not trigger the reserved-prefix warning")
    fun uncaughtDoesNotWarn() {
        configure()
        crash(IllegalStateException())
        assertTrue(warnings.none { it.contains("reserved") })
    }

    // ─── The previous handler is always called ───────────────────────────────

    @Test
    @DisplayName("Option off after a reconfigure: straight to the previous handler, nothing sent")
    fun optionOffForwards() {
        configure()
        configure(captureUncaught = false)
        val throwable = IllegalStateException()
        crash(throwable)

        assertEquals(listOf(throwable), previous.calls.map { it.second })
        assertTrue(uncaughtEvents().isEmpty())
    }

    @Test
    @DisplayName("setEnabled(false): straight to the previous handler, nothing buffered or sent")
    fun disabledForwards() {
        configure()
        Trackless.setEnabled(false)
        val throwable = IllegalStateException()
        crash(throwable)

        assertEquals(listOf(throwable), previous.calls.map { it.second })
        assertEquals(0, Trackless.bufferSizeForTesting())
        assertTrue(sentEvents().isEmpty())
    }

    @Test
    @DisplayName("After destroy(): straight to the previous handler, nothing sent")
    fun destroyedForwards() {
        configure()
        destroyAndWait()
        synchronized(sent) { sent.clear() }
        crash(IllegalStateException())

        assertEquals(1, previous.calls.size)
        assertTrue(sentEvents().isEmpty())
    }

    @Test
    @DisplayName("A send that throws: the previous handler is still called")
    fun sendThrows() {
        every { HttpClient.send(any(), any(), any()) } throws IllegalStateException("network down")
        configure()
        val throwable = IllegalStateException()
        crash(throwable)

        assertEquals(listOf(throwable), previous.calls.map { it.second })
    }

    @Test
    @DisplayName("A wait that throws: the previous handler is still called")
    fun waitThrows() {
        Trackless.replaceUncaughtCaptureForTesting(slot) { _, _ -> throw InterruptedException() }
        configure()
        crash(IllegalStateException())
        assertEquals(1, previous.calls.size)
    }

    @Test
    @DisplayName("A send slower than the cap: the wait gives up at 1.5 s and the previous handler runs while the send is still blocked")
    fun sendExceedsCap() {
        val release = CountDownLatch(1)
        val sendStarted = CountDownLatch(1)
        every { HttpClient.send(any(), any(), any()) } answers {
            sendStarted.countDown()
            release.await(5, TimeUnit.SECONDS)
            record(thirdArg())
            SendResult(statusCode = 200)
        }
        var waitedMs: Long? = null
        // The injected wait: sees the send start, then reports it unfinished, as a
        // 1.5 s wait on a hung send would.
        Trackless.replaceUncaughtCaptureForTesting(slot) { done, timeoutMs ->
            waitedMs = timeoutMs
            sendStarted.await(5, TimeUnit.SECONDS)
            done.await(0, TimeUnit.MILLISECONDS)
        }
        configure()
        crash(IllegalStateException())

        assertEquals(Trackless.UNCAUGHT_SEND_CAP_MS, waitedMs)
        assertEquals(1_500L, waitedMs)
        assertEquals(1, previous.calls.size)
        assertTrue(synchronized(sent) { sent.isEmpty() })

        release.countDown()
        assertEquals(1, uncaughtEvents().size)
    }

    @Test
    @DisplayName("The real wait returns after the cap on a hung send")
    fun realWaitIsCapped() {
        val release = CountDownLatch(1)
        every { HttpClient.send(any(), any(), any()) } answers {
            release.await(10, TimeUnit.SECONDS)
            SendResult(statusCode = 200)
        }
        configure()
        val startNs = System.nanoTime()
        crash(IllegalStateException())
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
        release.countDown()

        assertEquals(1, previous.calls.size)
        assertTrue(elapsedMs in 1_400L..4_000L, "waited ${elapsedMs}ms")
    }

    @Test
    @DisplayName("A fast send is waited for: it has gone out before the previous handler runs")
    fun fastSendFinishesFirst() {
        var sentBeforePrevious = false
        val checking = object : Thread.UncaughtExceptionHandler {
            override fun uncaughtException(t: Thread, e: Throwable) {
                sentBeforePrevious = synchronized(sent) { sent.isNotEmpty() }
            }
        }
        slot = FakeSlot(checking)
        Trackless.replaceUncaughtCaptureForTesting(slot)
        configure()
        crash(IllegalStateException())
        assertTrue(sentBeforePrevious)
    }

    @Test
    @DisplayName("No previous handler: the SDK prints as the platform would, and does not throw")
    fun noPreviousHandler() {
        slot = FakeSlot(null)
        Trackless.replaceUncaughtCaptureForTesting(slot)
        configure()
        val originalErr = System.err
        val captured = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(captured, true))
        try {
            crash(IllegalStateException(), Thread({}, "worker"))
        } finally {
            System.setErr(originalErr)
        }
        assertTrue(captured.toString().startsWith("Exception in thread \"worker\" java.lang.IllegalStateException"))
        assertEquals(1, uncaughtEvents().size)
    }

    // ─── Settings ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Changing captureUncaught counts as changed settings")
    fun optionIsASetting() {
        configure(captureUncaught = false)
        Trackless.startTimer("open")
        configure(captureUncaught = true)
        assertEquals(0, Trackless.openTimerCountForTesting())

        Trackless.startTimer("open")
        configure(captureUncaught = true)
        assertEquals(1, Trackless.openTimerCountForTesting())

        configure(captureUncaught = false)
        assertEquals(0, Trackless.openTimerCountForTesting())
    }

    @Test
    @DisplayName("Uncaught in the background: the error is sent, with no session end to add")
    fun uncaughtWithNoSession() {
        configure()
        val activity: Activity = mockk(relaxed = true)
        registered.last().onActivityStarted(activity)
        registered.last().onActivityStopped(activity)
        Trackless.awaitFlushesForTesting()
        synchronized(sent) { sent.clear() }

        crash(IllegalStateException())
        val events = sentEvents()
        assertEquals(1, events.count { it.name == Trackless.UNCAUGHT_ERROR })
        assertNull(events.firstOrNull { it.type == EventType.SESSION })
    }

    // ─── Reserved prefix on error() and info() ───────────────────────────────

    @Test
    @DisplayName("error() and info() warn once per process for a trackless. name, and send it")
    fun reservedPrefixOnErrorAndInfo() {
        configure()
        Trackless.error("Trackless.Uncaught", "boom")
        Trackless.info("trackless.tier", "paid")
        Trackless.error("api_timeout")

        val reserved = warnings.filter { it.contains("reserved") }
        assertEquals(1, reserved.size)
        assertTrue(reserved[0].startsWith("error name \"trackless.uncaught\""))
        flushAndWait()
        val names = synchronized(sent) { sent.flatMap { it.events } }.map { it.name }
        assertTrue(names.containsAll(listOf("trackless.uncaught", "trackless.tier", "api_timeout")))
    }

    @Test
    @DisplayName("info() names its call in the reserved-prefix warning")
    fun reservedPrefixOnInfo() {
        configure()
        Trackless.info("trackless.tier", "paid")
        assertEquals(listOf(true), warnings.filter { it.contains("reserved") }.map { it.startsWith("info name") })
    }
}
