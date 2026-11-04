package com.tracklesstelemetry.sdk

import android.app.Activity
import android.app.ActivityManager.RunningAppProcessInfo
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `measureLaunch`, `detectHangs` and the reserved `trackless.` prefix. Mirrors
 * the Swift LaunchAndHangTests and the web launch-and-hangs tests.
 *
 * Nothing sleeps: the launch probe reads a hand-driven clock, process start and
 * process importance, the first frame arrives when a test fires it, and the watchdog runs on a
 * [FakeWatchdogScheduler] whose ticks and main queue a test drains by hand.
 */
@DisplayName("Launch time and hangs")
class LaunchAndHangTest {

    private lateinit var context: Context
    private lateinit var application: Application

    private val testApiKey = "tl_0123456789abcdef0123456789abcdef"
    private val otherApiKey = "tl_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    private val warnings = mutableListOf<String>()
    private val sent = mutableListOf<EventPayload>()
    private val registered = mutableListOf<Application.ActivityLifecycleCallbacks>()
    private val pendingFrames = mutableListOf<() -> Unit>()
    private lateinit var scheduler: FakeWatchdogScheduler

    /** Hand-driven `elapsedRealtime`, in milliseconds. */
    private var nowMs = 10_000L
    private var processStartMs = 9_000L

    /** This process's importance as the probe reads it; null is a read that returned nothing. */
    private var importance: Int? = RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    private var importanceReads = 0
    private var importanceThrows = false

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
        warnings.clear()
        sent.clear()
        registered.clear()
        pendingFrames.clear()
        nowMs = 10_000L
        processStartMs = 9_000L
        importance = RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        importanceReads = 0
        importanceThrows = false
        Trackless.onWarning = { warnings.add(it) }
        Trackless.replaceTimerClockForTesting { nowMs }
        Trackless.replaceLaunchProbeForTesting(
            processStartMs = { processStartMs },
            clock = { nowMs },
            importance = {
                importanceReads++
                if (importanceThrows) throw SecurityException("no importance")
                importance
            },
            frameWaiter = { _, onFrame -> pendingFrames.add(onFrame) },
        )
        scheduler = FakeWatchdogScheduler()
        Trackless.replaceHangWatchdogForTesting(scheduler) { nowMs }
    }

    @AfterEach
    fun tearDown() {
        Trackless.resetForTesting()
        unmockkAll()
    }

    private fun configure(
        apiKey: String = testApiKey,
        enabled: Boolean = true,
        measureLaunch: Boolean = false,
        launchThresholdSeconds: Double? = null,
        detectHangs: Boolean = false,
    ) {
        Trackless.configure(
            context,
            TracklessConfig(
                apiKey = apiKey,
                endpoint = "https://api.test.com",
                enabled = enabled,
                flushIntervalSeconds = 999_999L,
                measureLaunch = measureLaunch,
                launchThresholdSeconds = launchThresholdSeconds,
                detectHangs = detectHangs,
            ),
        )
    }

    private val callbacks: Application.ActivityLifecycleCallbacks
        get() = registered.last()

    /** Open an Activity: started, then resumed. */
    private fun open(activity: Activity = mockk(relaxed = true)): Activity {
        callbacks.onActivityStarted(activity)
        callbacks.onActivityResumed(activity)
        return activity
    }

    /** Leave an Activity: paused, then stopped. */
    private fun leave(activity: Activity) {
        callbacks.onActivityPaused(activity)
        callbacks.onActivityStopped(activity)
    }

    private fun drawFrames() {
        val frames = pendingFrames.toList()
        pendingFrames.clear()
        frames.forEach { it() }
    }

    private fun sentEvents(): List<TracklessEvent> {
        flushAndWait()
        return synchronized(sent) { sent.flatMap { it.events } }
    }

    private fun sentPerformance(): List<TracklessEvent> =
        sentEvents().filter { it.type == EventType.PERFORMANCE }

    // ─── Launch: opt-in ──────────────────────────────────────────────────────

    @Test
    @DisplayName("measureLaunch off: a resumed Activity is not even watched for its first frame")
    fun launchOffSendsNothing() {
        configure()
        open()
        assertTrue(pendingFrames.isEmpty())
        assertTrue(sentPerformance().isEmpty())
    }

    @Test
    @DisplayName("Launch time runs from process start to the first frame, sent once per process")
    fun launchSentOnce() {
        configure(measureLaunch = true)
        val first = open()
        nowMs = 10_500L
        drawFrames()

        val events = sentPerformance()
        assertEquals(listOf(Trackless.LAUNCH_METRIC), events.map { it.name })
        assertEquals(listOf(1.5), events[0].durations)
        assertNull(events[0].threshold)

        // Another Activity, a return from the background, a reconfigure, destroy + configure: never again.
        sent.clear()
        open()
        leave(first)
        open(first)
        configure(apiKey = otherApiKey, measureLaunch = true)
        destroyAndWait()
        configure(measureLaunch = true)
        open()
        drawFrames()
        assertTrue(pendingFrames.isEmpty())
        assertTrue(sentPerformance().isEmpty())
    }

    @Test
    @DisplayName("launchThresholdSeconds is sent as the launch metric's threshold")
    fun launchThresholdPassedThrough() {
        configure(measureLaunch = true, launchThresholdSeconds = 2.0)
        open()
        drawFrames()

        val events = sentPerformance()
        assertEquals(listOf(1.0), events[0].durations)
        assertEquals(2.0, events[0].threshold)
    }

    @Test
    @DisplayName("A launch threshold that is not a positive number is ignored with a warning")
    fun invalidThresholdIgnored() {
        configure(measureLaunch = true, launchThresholdSeconds = -1.0)
        open()
        drawFrames()

        assertNull(sentPerformance()[0].threshold)
        assertTrue(warnings.any { it.contains("launchThresholdSeconds") })
    }

    @Test
    @DisplayName("Late configure: the first callback is a pause, so the Activity was already running")
    fun lateConfigureSendsNothing() {
        configure(measureLaunch = true)
        val running: Activity = mockk(relaxed = true)
        leave(running)
        assertFalse(Trackless.launchProbeWaitingForTesting)

        open(running)
        drawFrames()
        assertTrue(sentPerformance().isEmpty())
    }

    @Test
    @DisplayName("Late configure: an Activity already resumed while measureLaunch was off")
    fun lateConfigureAfterResumeSendsNothing() {
        configure()
        open()
        configure(measureLaunch = true)
        assertFalse(Trackless.launchProbeWaitingForTesting)

        open()
        drawFrames()
        assertTrue(sentPerformance().isEmpty())
    }

    @Test
    @DisplayName("A configure inside the first Activity's onStart still measures")
    fun configureBeforeResumeMeasures() {
        configure(measureLaunch = true)
        callbacks.onActivityResumed(mockk(relaxed = true))  // onStart was before configure
        drawFrames()
        assertEquals(listOf(Trackless.LAUNCH_METRIC), sentPerformance().map { it.name })
    }

    @Test
    @DisplayName("A trampoline Activity that never draws: the next Activity's first frame is the launch")
    fun trampolineActivity() {
        configure(measureLaunch = true)
        val trampoline = open()
        pendingFrames.clear()  // finished before drawing
        leave(trampoline)
        open()
        nowMs = 11_000L
        drawFrames()

        assertEquals(listOf(2.0), sentPerformance()[0].durations)
    }

    @Test
    @DisplayName("A launch measured while recording is disabled is not sent")
    fun launchWhileDisabledNotSent() {
        configure(measureLaunch = true)
        Trackless.setEnabled(false)
        Trackless.setEnabled(true)
        open()
        Trackless.setEnabled(false)
        drawFrames()
        Trackless.setEnabled(true)

        assertTrue(sentPerformance().isEmpty())
    }

    @Test
    @DisplayName("A reconfigure that turns measureLaunch off drops the launch")
    fun launchTurnedOff() {
        configure(measureLaunch = true)
        configure(measureLaunch = false)
        open()
        drawFrames()
        assertTrue(sentPerformance().isEmpty())
    }

    @Test
    @DisplayName("A launch time outside 0 to 86,400 seconds is dropped")
    fun launchOutOfRangeDropped() {
        processStartMs = 20_000L
        configure(measureLaunch = true)
        open()
        drawFrames()
        assertTrue(sentPerformance().isEmpty())
    }

    @Test
    @DisplayName("A launch of exactly 60 seconds is sent")
    fun launchAtCapSent() {
        configure(measureLaunch = true)
        open()
        nowMs = processStartMs + 60_000L
        drawFrames()
        assertEquals(listOf(60.0), sentPerformance()[0].durations)
    }

    @Test
    @DisplayName("A launch longer than 60 seconds is dropped, not clipped, and the probe finishes")
    fun launchOverCapDropped() {
        configure(measureLaunch = true)
        open()
        nowMs = processStartMs + 60_001L
        drawFrames()
        assertFalse(Trackless.launchProbeWaitingForTesting)
        assertTrue(sentPerformance().isEmpty())

        // A later Activity is not measured instead.
        nowMs = processStartMs + 1_000L
        open()
        drawFrames()
        assertTrue(pendingFrames.isEmpty())
        assertTrue(sentPerformance().isEmpty())
    }

    // ─── Launch: background starts, by this process's importance ─────────────
    //
    // On API 35 the first Activity is created only after Application.onCreate
    // has returned and the main queue has had a turn, so on a launcher cold start
    // anything configure() posts runs before the first resume. The decision is
    // therefore made inside arm(), from the importance it reads there.

    @Test
    @DisplayName("Cold start: the process is in the foreground at configure, so the launch is measured")
    fun foregroundColdStartMeasures() {
        configure(measureLaunch = true)
        // Application.onCreate returns; the first Activity is created and resumed after.
        open()
        nowMs = 10_500L
        drawFrames()

        val events = sentPerformance()
        assertEquals(listOf(Trackless.LAUNCH_METRIC), events.map { it.name })
        assertEquals(listOf(1.5), events[0].durations)
        assertEquals(1, importanceReads)
    }

    @Test
    @DisplayName("Background start (cached at configure): the probe finishes at arm, before anything else runs")
    fun backgroundStartSendsNothing() {
        importance = RunningAppProcessInfo.IMPORTANCE_CACHED  // 400, what a broadcast start reads
        configure(measureLaunch = true)
        assertFalse(Trackless.launchProbeWaitingForTesting)

        // The user opens the app later.
        nowMs = 15_000L
        open()
        drawFrames()
        assertTrue(pendingFrames.isEmpty())
        assertTrue(sentPerformance().isEmpty())
    }

    @Test
    @DisplayName("A foreground service start is not a launch either")
    fun foregroundServiceStartSendsNothing() {
        importance = RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
        configure(measureLaunch = true)
        assertFalse(Trackless.launchProbeWaitingForTesting)
        open()
        drawFrames()
        assertTrue(sentPerformance().isEmpty())
    }

    @Test
    @DisplayName("An importance read that throws or returns nothing leaves the probe measuring")
    fun unreadableImportanceMeasures() {
        importanceThrows = true
        configure(measureLaunch = true)
        assertTrue(Trackless.launchProbeWaitingForTesting)
        open()
        drawFrames()
        assertEquals(listOf(1.0), sentPerformance()[0].durations)

        Trackless.resetForTesting()
        Trackless.replaceLaunchProbeForTesting(
            processStartMs = { processStartMs },
            clock = { nowMs },
            importance = { null },
            frameWaiter = { _, onFrame -> pendingFrames.add(onFrame) },
        )
        sent.clear()
        configure(measureLaunch = true)
        assertTrue(Trackless.launchProbeWaitingForTesting)
    }

    @Test
    @DisplayName("The importance is read once, when the probe arms, and never with measureLaunch off")
    fun importanceReadOnceAtArm() {
        configure()
        assertEquals(0, importanceReads)
        configure(measureLaunch = true)
        assertEquals(1, importanceReads)
        configure(apiKey = otherApiKey, measureLaunch = true)
        open()
        drawFrames()
        assertEquals(1, importanceReads)
    }

    @Test
    @DisplayName("Late configure: no importance is read once an Activity has resumed")
    fun lateConfigureReadsNoImportance() {
        configure()
        open()
        configure(measureLaunch = true)
        assertEquals(0, importanceReads)
        assertFalse(Trackless.launchProbeWaitingForTesting)
    }

    @Test
    @DisplayName("SDK metrics leave the session's depth alone")
    fun sdkMetricsDoNotCountAsActivity() {
        configure(measureLaunch = true)
        open()
        drawFrames()
        destroyAndWait()

        val events = synchronized(sent) { sent.flatMap { it.events } }
        val end = events.first { it.type == EventType.SESSION && it.name == "end" }
        assertEquals(0, end.stepIndex)
    }

    @Test
    @DisplayName("Turning measureLaunch or detectHangs on counts as changed settings")
    fun optionsAreSettings() {
        configure()
        Trackless.startTimer("open")
        configure(detectHangs = true)
        assertEquals(0, Trackless.openTimerCountForTesting())

        Trackless.startTimer("open")
        configure(measureLaunch = true, detectHangs = true)
        assertEquals(0, Trackless.openTimerCountForTesting())

        Trackless.startTimer("open")
        configure(measureLaunch = true, detectHangs = true)
        assertEquals(1, Trackless.openTimerCountForTesting())
    }

    // ─── Hangs ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("detectHangs off: the watchdog never runs")
    fun hangsOffNeverRuns() {
        configure()
        open()
        assertFalse(Trackless.hangWatchdogForTesting.isRunning)
        assertEquals(0, scheduler.starts)
    }

    @Test
    @DisplayName("A main thread blocked for 250 ms or more is sent as trackless.hang on recovery")
    fun hangSent() {
        configure(detectHangs = true)
        open()
        assertTrue(Trackless.hangWatchdogForTesting.isRunning)

        scheduler.tick()
        assertEquals(1, scheduler.mainQueue.size)
        nowMs += 300
        scheduler.tick()  // still outstanding: no second ping
        nowMs += 300
        assertEquals(1, scheduler.mainQueue.size)
        scheduler.runMain()

        val events = sentPerformance()
        assertEquals(listOf(Trackless.HANG_METRIC), events.map { it.name })
        assertEquals(listOf(0.6), events[0].durations)
        assertNull(events[0].threshold)
    }

    @Test
    @DisplayName("A wait under 250 ms is not a hang")
    fun shortWaitIsNotAHang() {
        configure(detectHangs = true)
        open()

        scheduler.tick()
        nowMs += 249
        scheduler.runMain()
        scheduler.tick()
        nowMs += 250
        scheduler.runMain()

        assertEquals(listOf(listOf(0.25)), sentPerformance().map { it.durations })
    }

    @Test
    @DisplayName("Background stops the watchdog, settling a hang the user left during; a resume restarts it")
    fun backgroundStopsWatchdog() {
        configure(detectHangs = true)
        val activity = open()
        scheduler.tick()
        nowMs += 1_000
        leave(activity)  // the last Activity stopped: background

        assertFalse(Trackless.hangWatchdogForTesting.isRunning)
        assertFalse(scheduler.ticking)
        assertTrue(scheduler.mainQueue.isEmpty())
        scheduler.tick()
        assertTrue(scheduler.mainQueue.isEmpty())

        open(activity)
        assertTrue(Trackless.hangWatchdogForTesting.isRunning)
        assertEquals(2, scheduler.starts)

        val hangs = sentPerformance().filter { it.name == Trackless.HANG_METRIC }
        assertEquals(listOf(1.0), hangs.flatMap { it.durations.orEmpty() })
    }

    @Test
    @DisplayName("setEnabled(false), a reconfigure without detectHangs and destroy() stop the watchdog")
    fun hangsStopWithTheSdk() {
        configure(detectHangs = true)
        open()
        Trackless.setEnabled(false)
        assertFalse(Trackless.hangWatchdogForTesting.isEnabled)
        Trackless.setEnabled(true)
        open()
        assertTrue(Trackless.hangWatchdogForTesting.isRunning)

        configure(apiKey = otherApiKey)
        assertFalse(Trackless.hangWatchdogForTesting.isEnabled)
        open()
        assertFalse(Trackless.hangWatchdogForTesting.isRunning)

        configure(detectHangs = true)
        open()
        assertTrue(Trackless.hangWatchdogForTesting.isRunning)
        destroyAndWait()
        assertFalse(Trackless.hangWatchdogForTesting.isRunning)
        assertTrue(scheduler.shutdowns >= 3)
    }

    @Test
    @DisplayName("A hang reported while recording is disabled is not sent")
    fun hangWhileDisabledNotSent() {
        configure(detectHangs = true)
        open()
        scheduler.tick()
        nowMs += 500
        Trackless.setEnabled(false)
        scheduler.runMain()
        assertEquals(0, Trackless.bufferSizeForTesting())
    }

    // ─── Reserved prefix ─────────────────────────────────────────────────────

    @Test
    @DisplayName("performance() warns once per process for a trackless. name, and sends it")
    fun reservedPrefixWarnsOnce() {
        configure()
        Trackless.performance("Trackless.Custom", 1.0)
        Trackless.performance("trackless.other", 1.0)
        Trackless.startTimer("trackless.timer")
        Trackless.performance("not_reserved", 1.0)
        configure(apiKey = otherApiKey)
        Trackless.performance("trackless.again", 1.0)

        val reserved = warnings.filter { it.contains("reserved") }
        assertEquals(1, reserved.size)
        assertTrue(reserved[0].contains("trackless.custom"))
        assertTrue(sentPerformance().map { it.name }.containsAll(listOf("trackless.custom", "trackless.other", "trackless.again")))
    }

    @Test
    @DisplayName("startTimer warns for a trackless. name")
    fun reservedPrefixStartTimer() {
        configure()
        Trackless.startTimer("trackless.checkout")
        assertEquals(1, warnings.count { it.contains("reserved") })
    }

    @Test
    @DisplayName("measure warns for a trackless. name, and sends it")
    fun reservedPrefixMeasure() {
        configure()
        Trackless.measure("trackless.parse") { nowMs += 500 }
        assertEquals(1, warnings.count { it.contains("reserved") })
        assertEquals(listOf("trackless.parse"), sentPerformance().map { it.name })
    }

    @Test
    @DisplayName("The SDK's own launch and hang sends do not trigger the reserved-prefix warning")
    fun sdkSendsDoNotWarn() {
        configure(measureLaunch = true, detectHangs = true)
        open()
        drawFrames()
        scheduler.tick()
        nowMs += 400
        scheduler.runMain()

        assertEquals(2, sentPerformance().size)
        assertTrue(warnings.none { it.contains("reserved") })

        Trackless.performance("trackless.mine", 1.0)
        assertEquals(1, warnings.count { it.contains("reserved") })
    }

    // ─── Watchdog unit ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Watchdog: does not start until enabled; disable stops it and its thread")
    fun watchdogEnableDisable() {
        val fake = FakeWatchdogScheduler()
        val watchdog = HangWatchdog(fake) { nowMs }
        watchdog.appBecameActive()
        assertFalse(watchdog.isRunning)

        watchdog.enable { }
        watchdog.appBecameActive()
        assertTrue(watchdog.isRunning)
        watchdog.disable()
        assertFalse(watchdog.isRunning)
        assertEquals(1, fake.shutdowns)
    }

    @Test
    @DisplayName("Watchdog: a handler that throws never escapes to the main thread")
    fun watchdogNeverThrows() {
        val fake = FakeWatchdogScheduler()
        val watchdog = HangWatchdog(fake) { nowMs }
        watchdog.enable { throw IllegalStateException("boom") }
        watchdog.appBecameActive()
        fake.tick()
        nowMs += 1_000
        fake.runMain()
        watchdog.onTick()
        nowMs += 1_000
        watchdog.appResignedActive()
    }
}

/** A [WatchdogScheduler] driven by hand: ticks and the main queue run only when a test says. */
internal class FakeWatchdogScheduler : WatchdogScheduler {
    private var tickRunnable: Runnable? = null
    var ticking = false
        private set
    var starts = 0
        private set
    var shutdowns = 0
        private set
    val mainQueue = mutableListOf<Runnable>()

    override fun startTicks(tick: Runnable, intervalMs: Long) {
        tickRunnable = tick
        ticking = true
        starts++
    }

    override fun stopTicks() {
        ticking = false
    }

    override fun postToMain(runnable: Runnable) {
        mainQueue.add(runnable)
    }

    override fun removeFromMain(runnable: Runnable) {
        mainQueue.remove(runnable)
    }

    override fun shutdown() {
        ticking = false
        shutdowns++
    }

    fun tick() {
        if (ticking) tickRunnable?.run()
    }

    fun runMain() {
        val queued = mainQueue.toList()
        mainQueue.clear()
        queued.forEach { it.run() }
    }
}
