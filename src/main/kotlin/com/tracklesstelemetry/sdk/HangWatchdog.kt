package com.tracklesstelemetry.sdk

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock

/**
 * Where the watchdog's work runs: the repeating tick on a background thread,
 * the ping on the main thread. Injectable so tests drive both by hand.
 */
internal interface WatchdogScheduler {
    /** Run [tick] every [intervalMs] on a background thread until [stopTicks]. */
    fun startTicks(tick: Runnable, intervalMs: Long)

    /** Stop the ticks. The background thread may stay, idle. */
    fun stopTicks()

    /** Run [runnable] on the main thread. */
    fun postToMain(runnable: Runnable)

    /** Remove [runnable] from the main thread's queue if it has not run. */
    fun removeFromMain(runnable: Runnable)

    /** Stop the background thread. */
    fun shutdown()
}

/** Production scheduler: a background [HandlerThread] and a [Handler] on the main [Looper]. */
internal class LooperWatchdogScheduler : WatchdogScheduler {
    private var thread: HandlerThread? = null
    private var tickHandler: Handler? = null
    private var repeating: Runnable? = null
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    @Synchronized
    override fun startTicks(tick: Runnable, intervalMs: Long) {
        val handler = tickHandler ?: run {
            val started = HandlerThread("trackless-watchdog", Process.THREAD_PRIORITY_BACKGROUND)
            started.start()
            thread = started
            Handler(started.looper).also { tickHandler = it }
        }
        repeating?.let { handler.removeCallbacks(it) }
        val next = object : Runnable {
            override fun run() {
                tick.run()
                handler.postDelayed(this, intervalMs)
            }
        }
        repeating = next
        handler.postDelayed(next, intervalMs)
    }

    @Synchronized
    override fun stopTicks() {
        repeating?.let { tickHandler?.removeCallbacks(it) }
        repeating = null
    }

    override fun postToMain(runnable: Runnable) {
        mainHandler.post(runnable)
    }

    override fun removeFromMain(runnable: Runnable) {
        mainHandler.removeCallbacks(runnable)
    }

    @Synchronized
    override fun shutdown() {
        stopTicks()
        thread?.quitSafely()
        thread = null
        tickHandler = null
    }
}

/**
 * The main-thread watchdog behind [TracklessConfig.detectHangs].
 *
 * Every [INTERVAL_MS] a background tick posts a no-op to the main thread and
 * records when it did. When the main thread runs it, the wait is measured; a
 * wait of [THRESHOLD_MS] (250 ms) or more is reported once, when the main
 * thread has recovered, as one `trackless.hang` sample in seconds. While a ping
 * is outstanding no other is posted, so a long hang costs one ping. The tick
 * and the ping are allocated once, so a tick allocates nothing.
 *
 * **Only while the app is in the foreground.** [appBecameActive] starts the
 * ticks and [appResignedActive] stops them; the background thread then sits
 * idle with no wake-ups. Both are called from Activity lifecycle callbacks on
 * the main thread. Nothing here throws into the host.
 *
 * @param clock Millisecond `elapsedRealtime` clock — injectable for testing.
 */
internal class HangWatchdog(
    private val scheduler: WatchdogScheduler = LooperWatchdogScheduler(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var onHang: ((Double) -> Unit)? = null
    private var running = false
    private var outstanding = false
    private var sentAtMs = 0L

    private val tick = Runnable { onTick() }
    private val ping = Runnable { onPing() }

    /** Whether detection is switched on (test support). */
    val isEnabled: Boolean
        @Synchronized get() = onHang != null

    /** Whether the ticks are running (test support). */
    val isRunning: Boolean
        @Synchronized get() = running

    /** Switch detection on. Ticks start at the next [appBecameActive]. */
    @Synchronized
    fun enable(onHang: (Double) -> Unit) {
        this.onHang = onHang
    }

    /** Switch detection off, stop the ticks and the background thread. */
    fun disable() {
        try {
            synchronized(this) {
                onHang = null
                running = false
                outstanding = false
            }
            scheduler.shutdown()
            scheduler.removeFromMain(ping)
        } catch (_: Throwable) {
            // Never throws
        }
    }

    /** The app is in the foreground: start ticking, when detection is on. */
    fun appBecameActive() {
        try {
            synchronized(this) {
                if (onHang == null || running) return
                running = true
            }
            scheduler.startTicks(tick, INTERVAL_MS)
        } catch (_: Throwable) {
            // Never throws
        }
    }

    /**
     * The app went to the background: stop ticking. Called on the main
     * thread, so it is free now — a ping still outstanding has finished
     * waiting and is settled, so a hang the user left the app during counts.
     */
    fun appResignedActive() {
        try {
            val hang: Double?
            val handler: ((Double) -> Unit)?
            synchronized(this) {
                if (!running) return
                hang = settle()
                handler = onHang
                running = false
            }
            scheduler.stopTicks()
            scheduler.removeFromMain(ping)
            if (hang != null) handler?.invoke(hang)
        } catch (_: Throwable) {
            // Never throws
        }
    }

    /** One tick, on the background thread: ping the main thread unless a ping is outstanding. */
    internal fun onTick() {
        try {
            synchronized(this) {
                if (!running || outstanding) return
                outstanding = true
                sentAtMs = clock()
            }
            scheduler.postToMain(ping)
        } catch (_: Throwable) {
            // Never throws
        }
    }

    /** The main thread ran the ping. */
    private fun onPing() {
        try {
            val hang: Double?
            val handler: ((Double) -> Unit)?
            synchronized(this) {
                hang = settle()
                handler = onHang
            }
            if (hang != null) handler?.invoke(hang)
        } catch (_: Throwable) {
            // Never throws into the main thread
        }
    }

    /** Close the outstanding ping, if any; its wait in seconds when that was a hang. */
    private fun settle(): Double? {
        if (!outstanding) return null
        outstanding = false
        val waitedMs = clock() - sentAtMs
        return if (waitedMs >= THRESHOLD_MS) waitedMs / 1000.0 else null
    }

    companion object {
        /** A main-thread wait at or above this is a hang (Apple's definition, used on every platform). */
        const val THRESHOLD_MS = 250L

        /** How often the watchdog pings the main thread. */
        const val INTERVAL_MS = 250L
    }
}
